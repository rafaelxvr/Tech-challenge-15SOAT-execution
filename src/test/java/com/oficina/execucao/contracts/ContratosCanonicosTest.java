package com.oficina.execucao.contracts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate G1.5 (UNR-64): valida o conjunto canônico de contratos v1 da saga. A mesma classe roda nos três
 * repositórios (os-service, billing, execution); o conjunto é idêntico byte a byte e o manifesto
 * {@code CONTRACTS.sha256} é comparável entre os CIs.
 */
class ContratosCanonicosTest {
    static final Path RAIZ = Path.of("contracts");
    static final Set<String> NAO_LISTADOS = Set.of("CONTRACTS.sha256", "README.md");
    static final ObjectMapper JSON = new ObjectMapper();

    static JsonSchema schema(String tipo) throws IOException {
        var config = new SchemaValidatorsConfig();
        config.setFormatAssertionsEnabled(true);
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(Files.readString(RAIZ.resolve(tipo).resolve("v1.json")), config);
    }

    static JsonNode ler(Path arquivo) throws IOException {
        return JSON.readTree(Files.readString(arquivo));
    }

    static JsonNode fixture(String tipo) throws IOException {
        return ler(RAIZ.resolve("fixtures").resolve(tipo + ".json"));
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    static Map<String, String> manifesto() throws IOException {
        Map<String, String> entradas = new TreeMap<>();
        for (String linha : Files.readAllLines(RAIZ.resolve("CONTRACTS.sha256"), StandardCharsets.UTF_8)) {
            String[] partes = linha.split("  ", 2);
            assertThat(partes).as(linha).hasSize(2);
            assertThat(entradas.put(partes[1], partes[0])).as("entrada duplicada " + partes[1]).isNull();
        }
        return entradas;
    }

    static Map<String, JsonNode> tipos() throws IOException {
        Map<String, JsonNode> tipos = new TreeMap<>();
        ler(RAIZ.resolve("ownership.json")).get("types").fields()
                .forEachRemaining(e -> tipos.put(e.getKey(), e.getValue()));
        return tipos;
    }

    static Stream<String> tiposDeMensagem() throws IOException {
        return tipos().keySet().stream().filter(t -> !t.equals("envelope"));
    }

    @Test
    void checksumDeCadaArquivoConfereComOManifesto() throws Exception {
        Map<String, String> esperado = manifesto();
        Map<String, String> real = new TreeMap<>();
        try (Stream<Path> arquivos = Files.walk(RAIZ)) {
            for (Path p : arquivos.filter(Files::isRegularFile).toList()) {
                if (NAO_LISTADOS.contains(p.getFileName().toString())) continue;
                byte[] bytes = Files.readAllBytes(p);
                assertThat(new String(bytes, StandardCharsets.UTF_8)).as(p + " deve usar LF").doesNotContain("\r");
                real.put(RAIZ.relativize(p).toString().replace('\\', '/'), sha256(bytes));
            }
        }
        assertThat(real).isEqualTo(esperado);
    }

    @Test
    void identificadorDoConjuntoPodeSerComparadoNosTresCis() throws Exception {
        String conjunto = sha256(Files.readAllBytes(RAIZ.resolve("CONTRACTS.sha256")));
        System.out.println("CONTRACT_SET_SHA256=" + conjunto);
        String esperado = System.getenv("CONTRACT_SET_SHA256");
        if (esperado != null && !esperado.isBlank()) {
            assertThat(conjunto).as("conjunto de contratos diverge do pinado no CI").isEqualTo(esperado);
        }
    }

    @Test
    void todoTipoDeclaradoTemSchemaFixtureEDonoValido() throws Exception {
        Set<String> donos = Set.of("os", "billing", "execution", "billing|execution");
        for (var e : tipos().entrySet()) {
            assertThat(RAIZ.resolve(e.getKey()).resolve("v1.json")).as(e.getKey()).exists();
            assertThat(donos).contains(e.getValue().get("owner").asText());
            if (!e.getKey().equals("envelope")) {
                assertThat(RAIZ.resolve("fixtures").resolve(e.getKey() + ".json")).as(e.getKey()).exists();
            }
        }
        Set<String> declarados = new HashSet<>(tipos().keySet());
        try (Stream<Path> dirs = Files.list(RAIZ)) {
            dirs.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(n -> !n.equals("fixtures")).forEach(n -> assertThat(declarados).contains(n));
        }
    }

    @Test
    void comandoPertenceAoConsumidorERespostaAoProdutor() throws Exception {
        var tipos = tipos();
        for (var e : tipos.entrySet()) {
            String kind = e.getValue().get("kind").asText();
            String owner = e.getValue().get("owner").asText();
            if (kind.equals("COMMAND")) {
                assertThat(owner).as(e.getKey()).isIn("billing", "execution");
            }
            if (kind.equals("REPLY") && !e.getKey().equals("CompensacaoFalhou")) {
                var respondido = tipos.get(e.getValue().get("answers").asText());
                assertThat(respondido).as(e.getKey()).isNotNull();
                assertThat(owner).as("%s deve ser emitida pelo consumidor do comando".formatted(e.getKey()))
                        .isEqualTo(respondido.get("owner").asText());
            }
        }
        assertThat(tipos.get("envelope").get("owner").asText()).isEqualTo("os");
    }

    @ParameterizedTest
    @MethodSource("tiposDeMensagem")
    void fixtureRespeitaEnvelopeEPayloadFechado(String tipo) throws Exception {
        JsonNode fixture = fixture(tipo);
        JsonNode dono = tipos().get(tipo);
        assertThat(schema("envelope").validate(fixture)).as("envelope " + tipo).isEmpty();
        assertThat(schema(tipo).validate(fixture.get("payload"))).as("payload " + tipo).isEmpty();
        assertThat(fixture.get("type").asText()).isEqualTo(tipo);
        assertThat(fixture.get("kind").asText()).isEqualTo(dono.get("kind").asText());
        assertThat(fixture.get("schemaVersion").asInt()).isEqualTo(1);
        assertThat(fixture.get("passo").asInt()).isEqualTo(dono.get("passo").asInt());
        if (dono.get("kind").asText().equals("REPLY")) {
            assertThat(fixture.get("causationId").isTextual()).as("resposta aponta o comando respondido").isTrue();
        } else {
            assertThat(fixture.get("causationId").isNull()).isTrue();
        }
    }

    @ParameterizedTest
    @MethodSource("tiposDeMensagem")
    void payloadRejeitaCampoDesconhecidoEFaltaDeCampoObrigatorio(String tipo) throws Exception {
        JsonSchema schema = schema(tipo);
        ObjectNode comExtra = fixture(tipo).get("payload").deepCopy();
        comExtra.put("campoDesconhecido", "x");
        assertThat(schema.validate(comExtra)).as("campo extra em " + tipo).isNotEmpty();

        List<String> obrigatorios = new ArrayList<>();
        ler(RAIZ.resolve(tipo).resolve("v1.json")).get("required").forEach(n -> obrigatorios.add(n.asText()));
        assertThat(obrigatorios).as(tipo).isNotEmpty();
        for (String campo : obrigatorios) {
            ObjectNode semCampo = fixture(tipo).get("payload").deepCopy();
            semCampo.remove(campo);
            assertThat(schema.validate(semCampo)).as(tipo + " sem " + campo).isNotEmpty();
        }
    }

    @Test
    void envelopeRejeitaCamposInvalidos() throws Exception {
        JsonSchema envelope = schema("envelope");
        JsonNode base = fixture("GerarOrcamento");
        Map<String, Consumer<ObjectNode>> mutacoes = Map.of(
                "campo extra", n -> n.put("extra", 1),
                "sem messageId", n -> n.remove("messageId"),
                "kind desconhecido", n -> n.put("kind", "QUERY"),
                "passo fora da faixa", n -> n.put("passo", 8),
                "messageId nao uuid", n -> n.put("messageId", "abc"),
                "traceparent invalido", n -> n.put("traceparent", "00-xyz"),
                "payload nao objeto", n -> n.put("payload", "texto"),
                "occurredAt invalido", n -> n.put("occurredAt", "ontem"));
        mutacoes.forEach((nome, mutacao) -> {
            ObjectNode copia = base.deepCopy();
            mutacao.accept(copia);
            assertThat(envelope.validate(copia)).as(nome).isNotEmpty();
        });
    }

    @Test
    void respostaCopiaCorrelacaoEPassoDoComandoRespondido() throws Exception {
        var tipos = tipos();
        for (var e : tipos.entrySet()) {
            if (!e.getValue().get("kind").asText().equals("REPLY") || e.getKey().equals("CompensacaoFalhou")) continue;
            String respondido = e.getValue().get("answers").asText();
            JsonNode resposta = fixture(e.getKey());
            JsonNode comando = fixture(respondido);
            assertThat(resposta.get("passo")).as(e.getKey()).isEqualTo(comando.get("passo"));
            assertThat(resposta.get("causationId")).as(e.getKey()).isEqualTo(comando.get("messageId"));
            assertThat(resposta.get("sagaId")).as(e.getKey()).isEqualTo(comando.get("sagaId"));
            assertThat(resposta.get("osId")).as(e.getKey()).isEqualTo(comando.get("osId"));
            assertThat(resposta.get("correlationId")).as(e.getKey()).isEqualTo(comando.get("correlationId"));
        }
    }
}
