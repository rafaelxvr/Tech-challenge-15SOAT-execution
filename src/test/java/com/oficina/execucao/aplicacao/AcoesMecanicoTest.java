package com.oficina.execucao.aplicacao;

import com.oficina.execucao.adaptador.dynamo.DefinicaoTabelas;
import com.oficina.execucao.adaptador.dynamo.DynamoRegistroExecucoes;
import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.dominio.*;
import com.oficina.execucao.mensagem.*;
import com.oficina.execucao.support.Fixtures;
import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AcoesMecanicoTest extends LocalStackSupport {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T14:00:00Z"), ZoneOffset.UTC);
    static final MapeadorMensagens MAPEADOR = new MapeadorMensagens();
    static DynamoDbClient dynamo;
    static NomesTabelas nomes;
    static DynamoRegistroExecucoes registro;
    static AcoesMecanico acoes;
    static ConsultasExecucao consultas;

    @BeforeAll static void montar() {
        dynamo = dynamo();
        nomes = NomesTabelas.comPrefixo("mec-" + UUID.randomUUID().toString().substring(0, 8));
        DefinicaoTabelas.criarSeAusente(dynamo, nomes);
        registro = new DynamoRegistroExecucoes(dynamo, nomes, MAPEADOR, CLOCK);
        acoes = new AcoesMecanico(registro, new FabricaRespostas(CLOCK), new CapacidadeOficina(5), CLOCK);
        consultas = new ConsultasExecucao(registro, new Filiais(List.of("MATRIZ")));
    }

    @AfterAll static void fechar() { dynamo.close(); }

    Execucao gravar(Execucao e, boolean reservaVaga) {
        registro.gravar(new Gravacao(null, reservaVaga ? new Gravacao.AjusteCapacidade(e.filialId(), 1, 5) : null,
                List.of(e), null));
        return e;
    }

    List<Envelope> respostasDa(UUID osId) {
        return dynamo.query(b -> b.tableName(nomes.outbox()).indexName(DefinicaoTabelas.INDICE_OS_SEQUENCIA)
                        .keyConditionExpression("osId = :o").expressionAttributeValues(Map.of(":o", AttributeValue.fromS(osId.toString()))))
                .items().stream().map(i -> MAPEADOR.ler(i.get("corpo").s())).toList();
    }

    int vagas(String filial) {
        var item = dynamo.getItem(b -> b.tableName(nomes.execucoes()).consistentRead(true)
                .key(Map.of("execucaoId", AttributeValue.fromS("CAPACIDADE#" + filial)))).item();
        return item.containsKey("reparosAtivos") ? Integer.parseInt(item.get("reparosAtivos").n()) : 0;
    }

    @Test void concluirDiagnosticoRespondeComCausaDoIniciarDiagnostico() {
        var origem = Fixtures.origem();
        var e = gravar(Execucao.diagnostico(UUID.randomUUID(), 42, "MATRIZ", 2, origem, CLOCK.instant()), false);
        acoes.iniciar(e.id(), "mecanico@oficina.test");
        acoes.registrarTarefa(e.id(), "Scanner OBD sem falhas");
        var concluida = acoes.concluir(e.id());
        assertThat(concluida.status()).isEqualTo(StatusExecucao.CONCLUIDA);
        assertThat(registro.buscar(e.id()).orElseThrow().tarefas()).extracting(Tarefa::descricao)
                .containsExactly("Scanner OBD sem falhas");
        assertThat(respostasDa(e.osId())).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(TiposMensagem.DIAGNOSTICO_CONCLUIDO);
            assertThat(r.causationId()).isEqualTo(origem.messageId());
            assertThat(r.payload().get("execucaoId").asText()).isEqualTo(e.id().toString());
        });
    }

    @Test void concluirReparoRespondeComCausaDoIniciarReparo() {
        var origem = Fixtures.origem();
        var e = gravar(Execucao.reparo(UUID.randomUUID(), 42, "F-MEC", List.of(), origem, CLOCK.instant()), true);
        assertThat(vagas("F-MEC")).isEqualTo(1);
        acoes.iniciar(e.id(), "m");
        acoes.concluir(e.id());
        assertThat(vagas("F-MEC")).isZero();
        assertThat(respostasDa(e.osId())).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(TiposMensagem.EXECUCAO_FINALIZADA);
            assertThat(r.kind()).isEqualTo(Envelope.Kind.REPLY);
            assertThat(r.causationId()).isEqualTo(origem.messageId());
            assertThat(r.sagaId()).isEqualTo(origem.sagaId());
            assertThat(r.passo()).isEqualTo(origem.passo());
            assertThat(r.correlationId()).isEqualTo(origem.correlationId());
            assertThat(r.traceparent()).isEqualTo(origem.traceparent());
            assertThat(r.payload().get("concluidoEm").asText()).isEqualTo("2026-09-15T14:00:00Z");
        });
    }

    @Test void acoesInvalidasOuInexistentes() {
        var e = gravar(Execucao.diagnostico(UUID.randomUUID(), 42, "MATRIZ", 1, Fixtures.origem(), CLOCK.instant()), false);
        assertThatThrownBy(() -> acoes.concluir(e.id())).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> acoes.iniciar(UUID.randomUUID(), "m")).isInstanceOf(NaoEncontradaException.class);
        assertThat(respostasDa(e.osId())).isEmpty();
    }

    @Test void consultasDeFilaEPorOs() {
        var os = UUID.randomUUID();
        var e = gravar(Execucao.diagnostico(os, 42, "MATRIZ", 1, Fixtures.origem(), CLOCK.instant()), false);
        assertThat(consultas.fila("MATRIZ")).extracting(Execucao::id).contains(e.id());
        assertThat(consultas.porOs(os)).extracting(Execucao::id).containsExactly(e.id());
        assertThatThrownBy(() -> consultas.fila("OUTRA")).isInstanceOf(NaoEncontradaException.class);
    }
}
