package com.oficina.execucao.adaptador.dynamo;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.execucao.aplicacao.ConflitoConcorrenciaException;
import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.Tarefa;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.support.Fixtures;
import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamoRegistroExecucoesTest extends LocalStackSupport {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");
    static DynamoDbClient dynamo;
    static NomesTabelas nomes;
    static DynamoRegistroExecucoes registro;
    final FabricaRespostas respostas = new FabricaRespostas(Clock.fixed(T, ZoneOffset.UTC));

    @BeforeAll static void tabelas() {
        dynamo = dynamo();
        nomes = NomesTabelas.comPrefixo("reg-" + UUID.randomUUID().toString().substring(0, 8));
        DefinicaoTabelas.criarSeAusente(dynamo, nomes);
        registro = new DynamoRegistroExecucoes(dynamo, nomes, new MapeadorMensagens(), Clock.fixed(T, ZoneOffset.UTC));
    }

    @AfterAll static void fechar() { dynamo.close(); }

    static Execucao reparo(String filial) {
        return Execucao.reparo(UUID.randomUUID(), 1, filial, List.of(new Tarefa("Óleo", 1, T)), Fixtures.origem(), T);
    }

    static Gravacao.MarcaInbox inbox() { return new Gravacao.MarcaInbox(UUID.randomUUID(), "IniciarReparo"); }

    int contador(String filial) {
        var item = dynamo.getItem(b -> b.tableName(nomes.execucoes()).consistentRead(true)
                .key(Map.of("execucaoId", AttributeValue.fromS("CAPACIDADE#" + filial)))).item();
        return item.containsKey("reparosAtivos") ? Integer.parseInt(item.get("reparosAtivos").n()) : 0;
    }

    int outboxDaOs(UUID osId) {
        return dynamo.query(b -> b.tableName(nomes.outbox()).indexName(DefinicaoTabelas.INDICE_OS_SEQUENCIA)
                .keyConditionExpression("osId = :o")
                .expressionAttributeValues(Map.of(":o", AttributeValue.fromS(osId.toString())))).count();
    }

    static <R> R comRetentativa(Supplier<R> acao) {
        for (int i = 0; ; i++) {
            try {
                return acao.get();
            } catch (ConflitoConcorrenciaException e) {
                if (i > 50) throw e;
                try { Thread.sleep(20); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
    }

    @Test void gravaELePorIdOsEFila() {
        var e = reparo("F-LEITURA");
        assertThat(registro.gravar(new Gravacao(inbox(), null, List.of(e), null))).isEqualTo(ResultadoGravacao.GRAVADO);
        var lida = registro.buscar(e.id()).orElseThrow();
        assertThat(lida.dados()).usingRecursiveComparison().ignoringFields("versao").isEqualTo(e.dados());
        assertThat(lida.versao()).isEqualTo(1);
        assertThat(registro.porOs(e.osId())).extracting(Execucao::id).containsExactly(e.id());
        lida.iniciar("m", T);
        registro.gravar(new Gravacao(null, null, List.of(lida), null));
        var outra = reparo("F-LEITURA");
        registro.gravar(new Gravacao(null, null, List.of(outra), null));
        assertThat(registro.fila("F-LEITURA")).extracting(Execucao::id).containsExactly(e.id(), outra.id());
        assertThat(registro.buscar(UUID.randomUUID())).isEmpty();
    }

    @Test void inboxDuplicadaNaoGravaNada() {
        var marca = inbox();
        var e = reparo("F-DUP");
        var resposta = respostas.responder(e.origem(), e.osId(), "ReparoIniciado", JsonNodeFactory.instance.objectNode());
        assertThat(registro.gravar(new Gravacao(marca, new Gravacao.AjusteCapacidade("F-DUP", 1, 5), List.of(e), resposta)))
                .isEqualTo(ResultadoGravacao.GRAVADO);
        var segunda = reparo("F-DUP");
        assertThat(registro.gravar(new Gravacao(marca, new Gravacao.AjusteCapacidade("F-DUP", 1, 5), List.of(segunda), resposta)))
                .isEqualTo(ResultadoGravacao.DUPLICADA);
        assertThat(registro.buscar(segunda.id())).isEmpty();
        assertThat(contador("F-DUP")).isEqualTo(1);
        assertThat(outboxDaOs(e.osId())).isEqualTo(1);
    }

    @Test void versaoDesatualizadaGeraConflito() {
        var e = reparo("F-VER");
        registro.gravar(new Gravacao(null, null, List.of(e), null));
        var a = registro.buscar(e.id()).orElseThrow();
        var b = registro.buscar(e.id()).orElseThrow();
        a.iniciar("m1", T);
        registro.gravar(new Gravacao(null, null, List.of(a), null));
        b.iniciar("m2", T);
        assertThatThrownBy(() -> registro.gravar(new Gravacao(null, null, List.of(b), null)))
                .isInstanceOf(ConflitoConcorrenciaException.class);
        assertThatThrownBy(() -> registro.gravar(new Gravacao(null, null, List.of(e), null)))
                .isInstanceOf(ConflitoConcorrenciaException.class);
    }

    @Test void reparosConcorrentesNaoUltrapassamCapacidade() throws Exception {
        int limite = 3;
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<ResultadoGravacao>> futuros = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            futuros.add(pool.submit(() -> comRetentativa(() -> registro.gravar(new Gravacao(inbox(),
                    new Gravacao.AjusteCapacidade("F-CORRIDA", 1, limite), List.of(reparo("F-CORRIDA")), null)))));
        }
        List<ResultadoGravacao> resultados = new ArrayList<>();
        for (var f : futuros) resultados.add(f.get(60, TimeUnit.SECONDS));
        pool.shutdown();
        assertThat(resultados).filteredOn(r -> r == ResultadoGravacao.GRAVADO).hasSize(3);
        assertThat(resultados).filteredOn(r -> r == ResultadoGravacao.CAPACIDADE_ESGOTADA).hasSize(5);
        assertThat(contador("F-CORRIDA")).isEqualTo(3);
        assertThat(registro.fila("F-CORRIDA")).hasSize(3);
    }

    @Test void liberarVagaDecrementaSemCondicao() {
        registro.gravar(new Gravacao(inbox(), new Gravacao.AjusteCapacidade("F-LIB", 1, 1), List.of(reparo("F-LIB")), null));
        assertThat(registro.gravar(new Gravacao(inbox(), new Gravacao.AjusteCapacidade("F-LIB", 1, 1),
                List.of(reparo("F-LIB")), null))).isEqualTo(ResultadoGravacao.CAPACIDADE_ESGOTADA);
        registro.gravar(new Gravacao(null, new Gravacao.AjusteCapacidade("F-LIB", -1, 1), List.of(), null));
        assertThat(contador("F-LIB")).isZero();
        assertThat(registro.gravar(new Gravacao(inbox(), new Gravacao.AjusteCapacidade("F-LIB", 1, 1),
                List.of(reparo("F-LIB")), null))).isEqualTo(ResultadoGravacao.GRAVADO);
    }

    @Test void respostaEntraNoOutboxPendente() {
        var e = reparo("F-OUT");
        var resposta = respostas.responder(e.origem(), e.osId(), "ReparoIniciado", JsonNodeFactory.instance.objectNode());
        registro.gravar(new Gravacao(null, null, List.of(), resposta));
        var item = dynamo.getItem(b -> b.tableName(nomes.outbox()).consistentRead(true)
                .key(Map.of("messageId", AttributeValue.fromS(resposta.messageId().toString())))).item();
        assertThat(item.get("estado").s()).isEqualTo("PENDING");
        assertThat(item.get("osId").s()).isEqualTo(e.osId().toString());
        assertThat(item.get("tentativas").n()).isEqualTo("0");
        assertThat(new MapeadorMensagens().ler(item.get("corpo").s())).isEqualTo(resposta);
        assertThat(registro.gravar(new Gravacao(null, null, List.of(), null))).isEqualTo(ResultadoGravacao.GRAVADO);
    }
}
