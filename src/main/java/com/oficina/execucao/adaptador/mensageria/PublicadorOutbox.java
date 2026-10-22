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
import software.amazon.awssdk.services.dynamodb.model.Select;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes PENDING replies oldest first, never ahead of an earlier PENDING/BLOCKED reply of the same order.
 * Concurrent pods may send the same item twice; SQS deduplication (messageId) and the os-service inbox absorb it.
 */
public class PublicadorOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(PublicadorOutbox.class);
    private static final Duration RETENCAO = Duration.ofDays(14);
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
        var candidatos = dynamo.query(b -> b.tableName(tabelas.outbox()).indexName(DefinicaoTabelas.INDICE_PENDENTES)
                .keyConditionExpression("estado = :p AND disponivelEm <= :agora")
                .expressionAttributeValues(Map.of(":p", AttributeValue.fromS("PENDING"),
                        ":agora", AttributeValue.fromN(Long.toString(agora.toEpochMilli()))))
                .scanIndexForward(true).limit(25)).items();
        for (var item : candidatos) {
            if (temAnteriorPendente(item)) continue;
            publicar(item, agora);
            return true;
        }
        return false;
    }

    private boolean temAnteriorPendente(Map<String, AttributeValue> item) {
        return dynamo.query(b -> b.tableName(tabelas.outbox()).indexName(DefinicaoTabelas.INDICE_OS_SEQUENCIA)
                .keyConditionExpression("osId = :o AND sequencia < :s")
                .filterExpression("estado IN (:p, :b)")
                .expressionAttributeValues(Map.of(":o", item.get("osId"), ":s", item.get("sequencia"),
                        ":p", AttributeValue.fromS("PENDING"), ":b", AttributeValue.fromS("BLOCKED")))
                .select(Select.COUNT)).count() > 0;
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
        try {
            dynamo.updateItem(b -> b.tableName(tabelas.outbox()).key(chave)
                    .updateExpression("SET estado = :pub, publicadoEm = :agora, sqsMessageId = :ack, expiraEm = :exp")
                    .conditionExpression("estado = :p")
                    .expressionAttributeValues(Map.of(":pub", AttributeValue.fromS("PUBLISHED"),
                            ":agora", AttributeValue.fromN(Long.toString(agora.toEpochMilli())),
                            ":ack", AttributeValue.fromS(ack), ":p", AttributeValue.fromS("PENDING"),
                            ":exp", AttributeValue.fromN(Long.toString(agora.plus(RETENCAO).getEpochSecond())))));
        } catch (ConditionalCheckFailedException outroPodJaMarcou) {
            return;
        }
    }

    private void falhar(Map<String, AttributeValue> chave, int tentativas, Instant agora) {
        var tentativa = backoff.proxima(tentativas, agora);
        dynamo.updateItem(b -> b.tableName(tabelas.outbox()).key(chave)
                .updateExpression("SET tentativas = :t, estado = :e, disponivelEm = :d, ultimoErro = :c")
                .expressionAttributeValues(Map.of(":t", AttributeValue.fromN(Integer.toString(tentativa.numero())),
                        ":e", AttributeValue.fromS(tentativa.bloqueada() ? "BLOCKED" : "PENDING"),
                        ":d", AttributeValue.fromN(Long.toString(tentativa.disponivelEm().toEpochMilli())),
                        ":c", AttributeValue.fromS("QUEUE_SEND_FAILED"))));
        MDC.put("event_name", "outbox_publicacao_falhou");
        MDC.put("error_code", "QUEUE_SEND_FAILED");
        try { LOG.warn("Falha ao publicar resposta do outbox"); } finally { MDC.remove("event_name"); MDC.remove("error_code"); }
    }
}
