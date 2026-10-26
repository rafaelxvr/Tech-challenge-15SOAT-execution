package com.oficina.execucao.aplicacao;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.execucao.adaptador.dynamo.DefinicaoTabelas;
import com.oficina.execucao.adaptador.dynamo.DynamoRegistroExecucoes;
import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
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

class ProcessadorComandosTest extends LocalStackSupport {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);
    static DynamoDbClient dynamo;
    static NomesTabelas nomes;
    static DynamoRegistroExecucoes registro;
    static ProcessadorComandos processador;
    static final MapeadorMensagens MAPEADOR = new MapeadorMensagens();

    @BeforeAll static void montar() {
        dynamo = dynamo();
        nomes = NomesTabelas.comPrefixo("cmd-" + UUID.randomUUID().toString().substring(0, 8));
        DefinicaoTabelas.criarSeAusente(dynamo, nomes);
        registro = new DynamoRegistroExecucoes(dynamo, nomes, MAPEADOR, CLOCK);
        var respostas = new FabricaRespostas(CLOCK);
        var filiais = new Filiais(List.of("MATRIZ", "PEQUENA"));
        var capacidade = new CapacidadeOficina(1);
        processador = new ProcessadorComandos(MAPEADOR,
                new IniciarDiagnostico(registro, respostas, filiais, CLOCK),
                new IniciarReparo(registro, respostas, filiais, capacidade, CLOCK),
                new CancelarExecucao(registro, respostas, capacidade, CLOCK));
    }

    @AfterAll static void fechar() { dynamo.close(); }

    static ObjectNode diagnostico(String filial) {
        return JsonNodeFactory.instance.objectNode().put("numero", 42).put("filialId", filial).put("versaoOrcamento", 1);
    }

    static ObjectNode reparo(String filial) {
        var p = JsonNodeFactory.instance.objectNode().put("numero", 42).put("filialId", filial);
        p.putArray("servicos").addObject().put("descricao", "Troca de óleo").put("quantidade", 1);
        return p;
    }

    static ObjectNode cancelar() { return JsonNodeFactory.instance.objectNode().put("motivo", "REPARO_RECUSADO"); }

    List<Envelope> respostasDa(UUID osId) {
        return dynamo.query(b -> b.tableName(nomes.outbox()).indexName(DefinicaoTabelas.INDICE_OS_SEQUENCIA)
                        .keyConditionExpression("osId = :o").expressionAttributeValues(Map.of(":o", AttributeValue.fromS(osId.toString()))))
                .items().stream().map(i -> MAPEADOR.ler(i.get("corpo").s())).toList();
    }

    int vagasEmUso(String filial) {
        var item = dynamo.getItem(b -> b.tableName(nomes.execucoes()).consistentRead(true)
                .key(Map.of("execucaoId", AttributeValue.fromS("CAPACIDADE#" + filial)))).item();
        return item.containsKey("reparosAtivos") ? Integer.parseInt(item.get("reparosAtivos").n()) : 0;
    }

    Envelope enviar(String tipo, UUID os, int passo, ObjectNode payload) {
        Envelope comando = Fixtures.comando(tipo, os, passo, payload);
        processador.processar(MAPEADOR.escrever(comando));
        return comando;
    }

    @Test void diagnosticoCriaExecucaoNaFilaSemResposta() {
        UUID os = UUID.randomUUID();
        Envelope comando = enviar(TiposMensagem.INICIAR_DIAGNOSTICO, os, 1, diagnostico("MATRIZ"));
        assertThat(registro.porOs(os)).singleElement().satisfies(e -> {
            assertThat(e.tipo()).isEqualTo(TipoExecucao.DIAGNOSTICO);
            assertThat(e.status()).isEqualTo(StatusExecucao.NA_FILA);
            assertThat(e.origem().messageId()).isEqualTo(comando.messageId());
        });
        assertThat(respostasDa(os)).isEmpty();
    }

    @Test void filialDesconhecidaRespondeDiagnosticoRecusado() {
        UUID os = UUID.randomUUID();
        Envelope comando = enviar(TiposMensagem.INICIAR_DIAGNOSTICO, os, 1, diagnostico("NAO_EXISTE"));
        assertThat(registro.porOs(os)).isEmpty();
        assertThat(respostasDa(os)).singleElement().satisfies(r -> {
            assertThat(r.type()).isEqualTo(TiposMensagem.DIAGNOSTICO_RECUSADO);
            assertThat(r.causationId()).isEqualTo(comando.messageId());
            assertThat(r.payload().get("motivo").asText()).isEqualTo("FILIAL_DESCONHECIDA");
        });
    }

    @Test void reparoComVagaRespondeIniciadoESemVagaRecusa() {
        UUID primeira = UUID.randomUUID();
        UUID segunda = UUID.randomUUID();
        Envelope aceito = enviar(TiposMensagem.INICIAR_REPARO, primeira, 6, reparo("PEQUENA"));
        enviar(TiposMensagem.INICIAR_REPARO, segunda, 6, reparo("PEQUENA"));
        var r1 = respostasDa(primeira).get(0);
        assertThat(r1.type()).isEqualTo(TiposMensagem.REPARO_INICIADO);
        assertThat(r1.causationId()).isEqualTo(aceito.messageId());
        assertThat(r1.passo()).isEqualTo(6);
        assertThat(r1.payload().get("execucaoId").asText()).isEqualTo(registro.porOs(primeira).get(0).id().toString());
        var r2 = respostasDa(segunda).get(0);
        assertThat(r2.type()).isEqualTo(TiposMensagem.REPARO_RECUSADO);
        assertThat(r2.payload().get("motivo").asText()).isEqualTo("CAPACIDADE_ESGOTADA");
        assertThat(registro.porOs(segunda)).singleElement().extracting(Execucao::status).isEqualTo(StatusExecucao.RECUSADA);
        assertThat(vagasEmUso("PEQUENA")).isEqualTo(1);
        enviar(TiposMensagem.CANCELAR_EXECUCAO, primeira, 6, cancelar());
    }

    @Test void reparoEmFilialDesconhecidaERecusado() {
        UUID os = UUID.randomUUID();
        enviar(TiposMensagem.INICIAR_REPARO, os, 6, reparo("NAO_EXISTE"));
        assertThat(respostasDa(os)).singleElement().satisfies(r ->
                assertThat(r.payload().get("motivo").asText()).isEqualTo("FILIAL_DESCONHECIDA"));
        assertThat(registro.porOs(os)).singleElement().extracting(Execucao::status).isEqualTo(StatusExecucao.RECUSADA);
    }

    @Test void cancelarReparoLiberaVagaEERepetivel() {
        UUID os = UUID.randomUUID();
        enviar(TiposMensagem.INICIAR_REPARO, os, 6, reparo("MATRIZ"));
        assertThat(vagasEmUso("MATRIZ")).isEqualTo(1);
        Envelope cancelamento = enviar(TiposMensagem.CANCELAR_EXECUCAO, os, 6, cancelar());
        assertThat(vagasEmUso("MATRIZ")).isZero();
        assertThat(registro.porOs(os)).singleElement().extracting(Execucao::status).isEqualTo(StatusExecucao.CANCELADA);
        enviar(TiposMensagem.CANCELAR_EXECUCAO, os, 6, cancelar());
        assertThat(vagasEmUso("MATRIZ")).isZero();
        var cancelamentos = respostasDa(os).stream().filter(r -> r.type().equals(TiposMensagem.EXECUCAO_CANCELADA)).toList();
        assertThat(cancelamentos).extracting(r -> r.payload().get("canceladas").asInt()).containsExactly(1, 0);
        assertThat(cancelamentos).anySatisfy(r -> assertThat(r.causationId()).isEqualTo(cancelamento.messageId()));
    }

    @Test void cancelarSemExecucaoRespondeZero() {
        UUID os = UUID.randomUUID();
        enviar(TiposMensagem.CANCELAR_EXECUCAO, os, 1, cancelar());
        assertThat(respostasDa(os)).singleElement().satisfies(r ->
                assertThat(r.payload().get("canceladas").asInt()).isZero());
    }

    @Test void comandoDuplicadoNaoCriaSegundaExecucaoNemResposta() {
        UUID os = UUID.randomUUID();
        Envelope comando = Fixtures.comando(TiposMensagem.INICIAR_REPARO, os, 6, reparo("MATRIZ"));
        String corpo = MAPEADOR.escrever(comando);
        processador.processar(corpo);
        processador.processar(corpo);
        assertThat(registro.porOs(os)).hasSize(1);
        assertThat(respostasDa(os)).hasSize(1);
        assertThat(vagasEmUso("MATRIZ")).isEqualTo(1);
        enviar(TiposMensagem.CANCELAR_EXECUCAO, os, 6, cancelar());
    }

    @Test void respostaOuTipoDesconhecidoSaoInvalidos() {
        UUID os = UUID.randomUUID();
        var resposta = new FabricaRespostas(CLOCK).responder(Fixtures.origem(), os, "ReparoIniciado",
                JsonNodeFactory.instance.objectNode());
        assertThatThrownBy(() -> processador.processar(MAPEADOR.escrever(resposta))).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> processador.processar(MAPEADOR.escrever(Fixtures.comando("Desconhecido", os, 1,
                JsonNodeFactory.instance.objectNode())))).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> processador.processar("{}")).isInstanceOf(MensagemInvalidaException.class);
    }
}
