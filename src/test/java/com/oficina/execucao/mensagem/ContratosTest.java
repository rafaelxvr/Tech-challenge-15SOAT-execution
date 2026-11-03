package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.oficina.execucao.dominio.MotivoRecusa;
import com.oficina.execucao.dominio.OrigemComando;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Os comandos canônicos de contracts/fixtures são lidos e as respostas produzidas respeitam contracts/{Tipo}/v1.json. */
class ContratosTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");
    final JsonSchemaFactory schemas = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    final ObjectMapper json = new ObjectMapper();
    final MapeadorMensagens mapeador = new MapeadorMensagens();
    final FabricaRespostas fabrica = new FabricaRespostas(Clock.fixed(T, ZoneOffset.UTC));

    Set<ValidationMessage> validar(String tipo, JsonNode node) throws Exception {
        var config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return schemas.getSchema(Files.readString(Path.of("contracts", tipo, "v1.json")), config).validate(node);
    }

    String fixture(String tipo) throws Exception {
        return Files.readString(Path.of("contracts/fixtures", tipo + ".json"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"IniciarDiagnostico", "IniciarReparo", "CancelarExecucao"})
    void comandosDoOsRespeitamContratoESaoLidos(String tipo) throws Exception {
        JsonNode node = json.readTree(fixture(tipo));
        assertThat(validar("envelope", node)).isEmpty();
        assertThat(validar(tipo, node.get("payload"))).isEmpty();
        Envelope envelope = mapeador.ler(fixture(tipo));
        switch (tipo) {
            case "IniciarDiagnostico" -> assertThat(ComandosExecucao.diagnostico(envelope.payload()))
                    .isEqualTo(new ComandosExecucao.DadosDiagnostico(42, "MATRIZ", 1));
            case "IniciarReparo" -> assertThat(ComandosExecucao.reparo(envelope.payload()).servicos())
                    .containsExactly(new ComandosExecucao.ServicoSolicitado("Troca de óleo", 1));
            default -> assertThat(ComandosExecucao.cancelamento(envelope.payload()).motivo()).isEqualTo("PAGAMENTO_RECUSADO");
        }
    }

    @Test void diagnosticoRecusadoSoEmiteFilialDesconhecida() {
        assertThat(PayloadsResposta.diagnosticoRecusado().get("motivo").asText()).isEqualTo("FILIAL_DESCONHECIDA");
    }

    @Test void respostasProduzidasRespeitamContratos() throws Exception {
        var origem = new OrigemComando(UUID.randomUUID(), UUID.randomUUID(), 6, UUID.randomUUID().toString(), null);
        UUID id = UUID.randomUUID();
        Map<String, ObjectNode> respostas = Map.of(
                TiposMensagem.DIAGNOSTICO_CONCLUIDO, PayloadsResposta.diagnosticoConcluido(id),
                TiposMensagem.DIAGNOSTICO_RECUSADO, PayloadsResposta.diagnosticoRecusado(),
                TiposMensagem.REPARO_INICIADO, PayloadsResposta.reparoIniciado(id),
                TiposMensagem.REPARO_RECUSADO, PayloadsResposta.reparoRecusado(MotivoRecusa.CAPACIDADE_ESGOTADA),
                TiposMensagem.EXECUCAO_FINALIZADA, PayloadsResposta.execucaoFinalizada(id, T),
                TiposMensagem.EXECUCAO_CANCELADA, PayloadsResposta.execucaoCancelada(2),
                TiposMensagem.COMPENSACAO_FALHOU, PayloadsResposta.compensacaoFalhou());
        for (var r : respostas.entrySet()) {
            JsonNode node = json.readTree(mapeador.escrever(fabrica.responder(origem, UUID.randomUUID(), r.getKey(), r.getValue())));
            assertThat(validar("envelope", node)).as(r.getKey()).isEmpty();
            assertThat(validar(r.getKey(), node.get("payload"))).as(r.getKey()).isEmpty();
            assertThat(node.get("kind").asText()).isEqualTo("REPLY");
            assertThat(node.get("causationId").asText()).isEqualTo(origem.messageId().toString());
            assertThat(node.get("passo").asInt()).isEqualTo(6);
        }
    }

    @Test void origemCopiaCamposDoComando() throws Exception {
        Envelope comando = mapeador.ler(fixture("IniciarDiagnostico"));
        var origem = FabricaRespostas.origem(comando);
        assertThat(origem.messageId()).isEqualTo(comando.messageId());
        assertThat(origem.sagaId()).isEqualTo(comando.sagaId());
        assertThat(origem.passo()).isEqualTo(1);
        assertThat(origem.correlationId()).isEqualTo(comando.correlationId());
        assertThat(origem.traceparent()).isEqualTo(comando.traceparent());
    }
}
