package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.adaptador.dynamo.DefinicaoTabelas;
import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
import com.oficina.execucao.aplicacao.porta.EnviadorMensagens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes PENDING replies oldest first, never ahead of an earlier PENDING/BLOCKED reply of the same order.
 * Ordering comes from the per-order queue item (see {@link DefinicaoTabelas#chaveFila}) in the base table, read with strong
 * consistency: only the head of that queue may be sent. The GSI only discovers candidates, so its eventual
 * consistency can delay a publication but never reorder it.
 * Concurrent pods may send the same head twice; SQS deduplication (messageId) and the os-service inbox absorb it.
 */
public class PublicadorOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(PublicadorOutbox.class);
    private static final Duration RETENCAO = Duration.ofDays(14);
    private static final int TAMANHO_PAGINA = 25;
    private static final int MAX_PAGINAS = 40;
    private final DynamoDbClient dynamo;
    private final NomesTabelas tabelas;
    private final EnviadorMensagens enviador;
    private final Clock clock;
    private final BackoffPublicacao backoff;

    public PublicadorOutbox(DynamoDbClient dynamo, NomesTabelas tabelas, EnviadorMensagens enviador, Clock clock,
                            BackoffPublicacao backoff) {
        this.dynamo = dynamo;
        this.tabelas = tabelas;
        this.enviador = enviador;
        this.clock = clock;
        this.backoff = backoff;
    }

    public synchronized boolean publicarProximo() {
        Instant agora = clock.instant();
        var consulta = QueryRequest.builder().tableName(tabelas.outbox()).indexName(DefinicaoTabelas.INDICE_PENDENTES)
                .keyConditionExpression("estado = :p AND disponivelEm <= :agora")
                .expressionAttributeValues(Map.of(":p", AttributeValue.fromS("PENDING"),
                        ":agora", AttributeValue.fromN(Long.toString(agora.toEpochMilli()))))
                .scanIndexForward(true).limit(TAMANHO_PAGINA).build();
        int paginas = 0;
        for (var pagina : dynamo.queryPaginator(consulta)) {
            for (var item : pagina.items()) {
                if (!ehCabecaDaFila(item)) continue;
                publicar(item, agora);
                return true;
            }
            if (++paginas >= MAX_PAGINAS) break;
        }
        return false;
    }

    private boolean ehCabecaDaFila(Map<String, AttributeValue> item) {
        var fila = dynamo.getItem(b -> b.tableName(tabelas.outbox()).consistentRead(true)
                .key(Map.of("messageId", AttributeValue.fromS(DefinicaoTabelas.chaveFila(item.get("osId").s()))))).item();
        if (!fila.containsKey("fila") || fila.get("fila").l().isEmpty()) return false;
        return fila.get("fila").l().get(0).s().equals(item.get("messageId").s());
    }

    private void publicar(Map<String, AttributeValue> item, Instant agora) {
        var chave = Map.of("messageId", item.get("messageId"));
        String ack;
        try {
            ack = enviador.enviar(UUID.fromString(item.get("osId").s()), UUID.fromString(item.get("messageId").s()),
                    item.get("corpo").s());
            if (ack == null || ack.isBlank()) throw new IllegalStateException("Missing queue acknowledgement");
        } catch (RuntimeException e) {
            falhar(chave, Integer.parseInt(item.get("tentativas").n()), agora);
            return;
        }
        var marcar = Update.builder().tableName(tabelas.outbox()).key(chave)
                .updateExpression("SET estado = :pub, publicadoEm = :agora, sqsMessageId = :ack, expiraEm = :exp")
                .conditionExpression("estado = :p")
                .expressionAttributeValues(Map.of(":pub", AttributeValue.fromS("PUBLISHED"),
                        ":agora", AttributeValue.fromN(Long.toString(agora.toEpochMilli())),
                        ":ack", AttributeValue.fromS(ack), ":p", AttributeValue.fromS("PENDING"),
                        ":exp", AttributeValue.fromN(Long.toString(agora.plus(RETENCAO).getEpochSecond())))).build();
        var avancar = Update.builder().tableName(tabelas.outbox())
                .key(Map.of("messageId", AttributeValue.fromS(DefinicaoTabelas.chaveFila(item.get("osId").s()))))
                .updateExpression("REMOVE fila[0]").conditionExpression("fila[0] = :m")
                .expressionAttributeValues(Map.of(":m", item.get("messageId"))).build();
        try {
            dynamo.transactWriteItems(b -> b.transactItems(TransactWriteItem.builder().update(marcar).build(),
                    TransactWriteItem.builder().update(avancar).build()));
        } catch (TransactionCanceledException outroPodJaMarcou) {
            return;
        }
    }

    private void falhar(Map<String, AttributeValue> chave, int tentativasLidas, Instant agora) {
        var tentativa = backoff.proxima(tentativasLidas, agora);
        try {
            registrarFalha(chave, tentativasLidas, tentativa);
        } catch (ConditionalCheckFailedException corridaJaResolvida) {
            return;
        }
        MDC.put("event_name", "outbox_publicacao_falhou");
        MDC.put("error_code", "QUEUE_SEND_FAILED");
        try { LOG.warn("Falha ao publicar resposta do outbox"); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
    }

    private void registrarFalha(Map<String, AttributeValue> chave, int tentativasLidas, BackoffPublicacao.Tentativa tentativa) {
        dynamo.updateItem(b -> b.tableName(tabelas.outbox()).key(chave)
                .updateExpression("SET tentativas = :t, estado = :e, disponivelEm = :d, ultimoErro = :c")
                .conditionExpression("estado = :atual AND tentativas = :lida")
                .expressionAttributeValues(Map.of(":atual", AttributeValue.fromS("PENDING"),
                        ":lida", AttributeValue.fromN(Integer.toString(tentativasLidas)),
                        ":t", AttributeValue.fromN(Integer.toString(tentativa.numero())),
                        ":e", AttributeValue.fromS(tentativa.bloqueada() ? "BLOCKED" : "PENDING"),
                        ":d", AttributeValue.fromN(Long.toString(tentativa.disponivelEm().toEpochMilli())),
                        ":c", AttributeValue.fromS("QUEUE_SEND_FAILED"))));
    }
}
