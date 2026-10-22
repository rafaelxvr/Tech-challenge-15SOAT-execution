package com.oficina.execucao.adaptador.dynamo;

import com.oficina.execucao.aplicacao.ConflitoConcorrenciaException;
import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.DadosExecucao;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.StatusExecucao;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.CancellationReason;
import software.amazon.awssdk.services.dynamodb.model.Put;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactionCanceledException;
import software.amazon.awssdk.services.dynamodb.model.TransactionConflictException;
import software.amazon.awssdk.services.dynamodb.model.Update;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.oficina.execucao.adaptador.dynamo.ItemExecucaoMapper.n;
import static com.oficina.execucao.adaptador.dynamo.ItemExecucaoMapper.s;

public class DynamoRegistroExecucoes implements RegistroExecucoes {
    private static final Duration RETENCAO = Duration.ofDays(14);
    private static final String CONDICAO_FALHOU = "ConditionalCheckFailed";
    private final DynamoDbClient dynamo;
    private final NomesTabelas tabelas;
    private final MapeadorMensagens mapeador;
    private final Clock clock;

    public DynamoRegistroExecucoes(DynamoDbClient dynamo, NomesTabelas tabelas, MapeadorMensagens mapeador, Clock clock) {
        this.dynamo = dynamo;
        this.tabelas = tabelas;
        this.mapeador = mapeador;
        this.clock = clock;
    }

    @Override
    public Optional<Execucao> buscar(UUID id) {
        var item = dynamo.getItem(b -> b.tableName(tabelas.execucoes()).consistentRead(true)
                .key(Map.of("execucaoId", s(id.toString())))).item();
        return item == null || item.isEmpty() ? Optional.empty() : Optional.of(Execucao.restaurar(ItemExecucaoMapper.deItem(item)));
    }

    @Override
    public List<Execucao> porOs(UUID osId) {
        return consultar(DefinicaoTabelas.INDICE_OS, "osId", osId.toString());
    }

    @Override
    public List<Execucao> fila(String filialId) {
        List<Execucao> fila = new ArrayList<>(consultar(DefinicaoTabelas.INDICE_FILA, "filaChave",
                ItemExecucaoMapper.chaveFila(filialId, StatusExecucao.EM_ANDAMENTO)));
        fila.addAll(consultar(DefinicaoTabelas.INDICE_FILA, "filaChave",
                ItemExecucaoMapper.chaveFila(filialId, StatusExecucao.NA_FILA)));
        return fila;
    }

    @Override
    public ResultadoGravacao gravar(Gravacao g) {
        Instant agora = clock.instant();
        List<TransactWriteItem> itens = new ArrayList<>();
        int indiceInbox = -1;
        int indiceCapacidade = -1;
        if (g.inbox() != null) {
            indiceInbox = itens.size();
            itens.add(inbox(g.inbox(), agora));
        }
        if (g.capacidade() != null) {
            indiceCapacidade = itens.size();
            itens.add(capacidade(g.capacidade()));
        }
        g.execucoes().forEach(e -> itens.add(execucao(e.dados())));
        if (g.resposta() != null) itens.add(outbox(g.resposta(), agora));
        if (itens.isEmpty()) return ResultadoGravacao.GRAVADO;
        try {
            dynamo.transactWriteItems(b -> b.transactItems(itens));
            return ResultadoGravacao.GRAVADO;
        } catch (TransactionCanceledException e) {
            List<CancellationReason> motivos = e.cancellationReasons();
            if (falhou(motivos, indiceInbox)) return ResultadoGravacao.DUPLICADA;
            if (falhou(motivos, indiceCapacidade)) return ResultadoGravacao.CAPACIDADE_ESGOTADA;
            throw new ConflitoConcorrenciaException();
        } catch (TransactionConflictException e) {
            throw new ConflitoConcorrenciaException();
        }
    }

    private List<Execucao> consultar(String indice, String atributo, String valor) {
        return dynamo.queryPaginator(b -> b.tableName(tabelas.execucoes()).indexName(indice)
                        .keyConditionExpression(atributo + " = :v").expressionAttributeValues(Map.of(":v", s(valor)))
                        .scanIndexForward(true))
                .items().stream().map(ItemExecucaoMapper::deItem).map(Execucao::restaurar).toList();
    }

    private TransactWriteItem inbox(Gravacao.MarcaInbox marca, Instant agora) {
        return TransactWriteItem.builder().put(Put.builder().tableName(tabelas.inbox())
                .item(Map.of("messageId", s(marca.messageId().toString()), "tipo", s(marca.tipo()),
                        "recebidoEm", n(agora.toEpochMilli()), "expiraEm", n(agora.plus(RETENCAO).getEpochSecond())))
                .conditionExpression("attribute_not_exists(messageId)").build()).build();
    }

    private TransactWriteItem capacidade(Gravacao.AjusteCapacidade ajuste) {
        var update = Update.builder().tableName(tabelas.execucoes())
                .key(Map.of("execucaoId", s("CAPACIDADE#" + ajuste.filialId())))
                .updateExpression("ADD reparosAtivos :d");
        if (ajuste.delta() > 0) {
            update.conditionExpression("attribute_not_exists(reparosAtivos) OR reparosAtivos < :limite")
                    .expressionAttributeValues(Map.of(":d", n(ajuste.delta()), ":limite", n(ajuste.limite())));
        } else {
            update.expressionAttributeValues(Map.of(":d", n(ajuste.delta())));
        }
        return TransactWriteItem.builder().update(update.build()).build();
    }

    private TransactWriteItem execucao(DadosExecucao d) {
        var put = Put.builder().tableName(tabelas.execucoes()).item(ItemExecucaoMapper.paraItem(d, d.versao() + 1));
        if (d.versao() == 0) {
            put.conditionExpression("attribute_not_exists(execucaoId)");
        } else {
            put.conditionExpression("versao = :v").expressionAttributeValues(Map.of(":v", n(d.versao())));
        }
        return TransactWriteItem.builder().put(put.build()).build();
    }

    private TransactWriteItem outbox(Envelope resposta, Instant agora) {
        String corpo = mapeador.escrever(resposta);
        if (corpo.getBytes(StandardCharsets.UTF_8).length > Envelope.MAX_BYTES) {
            throw new IllegalArgumentException("Envelope acima de 64 KB");
        }
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("messageId", s(resposta.messageId().toString()));
        item.put("osId", s(resposta.osId().toString()));
        item.put("sequencia", n(ChronoUnit.MICROS.between(Instant.EPOCH, agora)));
        item.put("estado", s("PENDING"));
        item.put("tentativas", n(0));
        item.put("disponivelEm", n(agora.toEpochMilli()));
        item.put("criadoEm", n(agora.toEpochMilli()));
        item.put("tipo", s(resposta.type()));
        item.put("corpo", s(corpo));
        return TransactWriteItem.builder().put(Put.builder().tableName(tabelas.outbox()).item(item)
                .conditionExpression("attribute_not_exists(messageId)").build()).build();
    }

    private static boolean falhou(List<CancellationReason> motivos, int indice) {
        return indice >= 0 && motivos != null && indice < motivos.size()
                && CONDICAO_FALHOU.equals(motivos.get(indice).code());
    }
}
