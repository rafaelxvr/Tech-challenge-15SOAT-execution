package com.oficina.execucao.logging;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.logstash.logback.argument.StructuredArguments;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Loads the real logback-spring.xml and inspects the JSON it writes. Only fictitious sentinels are used. */
class LogbackJsonConfigTest {
    /** Montada em tempo de execução para o literal não casar com regras de varredura de segredos. */
    private static final String CHAVE_AWS_FICTICIA = "AK" + "IA" + "ABCDEFGHIJKLMNOP";

    private static final ObjectMapper JSON = new ObjectMapper();
    private final PrintStream originalOut = System.out;
    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    private LoggerContext context;

    @BeforeEach
    void configurar() throws Exception {
        context = new LoggerContext();
        context.setMDCAdapter(MDC.getMDCAdapter());
        URL config = getClass().getClassLoader().getResource("logback-spring.xml");
        assertThat(config).as("logback-spring.xml on the classpath").isNotNull();
        JoranConfigurator configurator = new JoranConfigurator();
        configurator.setContext(context);
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        configurator.doConfigure(config);
    }

    @AfterEach
    void restaurar() {
        MDC.clear();
        context.stop();
        System.setOut(originalOut);
    }

    private JsonNode emitir(String mensagem, Object... argumentos) throws Exception {
        context.getLogger("teste").info(mensagem, argumentos);
        String saida = captured.toString(StandardCharsets.UTF_8).trim();
        assertThat(saida.lines().count()).isEqualTo(1);
        return JSON.readTree(saida);
    }

    @Test
    void escreveMensagemRealEPreservaCamposFixosEMdc() throws Exception {
        MDC.put("event_name", "comando_processado");
        MDC.put("error_code", "CODE_X");
        MDC.put("correlation_id", "corr-1");
        MDC.put("saga_id", "saga-1");
        MDC.put("os_id", "os-1");
        MDC.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        MDC.put("nao_permitido", "vazou");

        JsonNode json = emitir("Comando {} processado", "SENTINELA_MSG_REAL");

        assertThat(json.get("message").asText()).isEqualTo("Comando SENTINELA_MSG_REAL processado").isNotEqualTo("safe");
        assertThat(json.get("service").asText()).isEqualTo("execution-service");
        assertThat(json.get("level").asText()).isEqualTo("INFO");
        assertThat(json.get("environment").asText()).isNotBlank();
        assertThat(json.get("timestamp").asText()).isNotBlank();
        assertThat(json.get("event_name").asText()).isEqualTo("comando_processado");
        assertThat(json.get("error_code").asText()).isEqualTo("CODE_X");
        assertThat(json.get("correlation_id").asText()).isEqualTo("corr-1");
        assertThat(json.get("saga_id").asText()).isEqualTo("saga-1");
        assertThat(json.get("os_id").asText()).isEqualTo("os-1");
        assertThat(json.get("traceparent").asText()).isEqualTo("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertThat(json.has("version")).isTrue();
        assertThat(json.has("nao_permitido")).isFalse();
    }

    @Test
    void mascaraCamposEstruturadosPorCaminho() throws Exception {
        JsonNode json = emitir("campos {} {} {} {} {} {} {} {}",
                StructuredArguments.kv("password", "SENTINELA_SENHA"),
                StructuredArguments.kv("Authorization", "SENTINELA_AUTH"),
                StructuredArguments.kv("cookie", "SENTINELA_COOKIE"),
                StructuredArguments.kv("client_secret", "SENTINELA_CS"),
                StructuredArguments.kv("checkout_url", "SENTINELA_URL"),
                StructuredArguments.kv("cpf", "SENTINELA_CPF"),
                StructuredArguments.kv("email", "SENTINELA_EMAIL"),
                StructuredArguments.kv("telefone", "SENTINELA_TEL"));

        for (String campo : List.of("password", "Authorization", "cookie", "client_secret", "checkout_url", "cpf", "email", "telefone")) {
            assertThat(json.get(campo).asText()).as(campo).isEqualTo("[REDACTED]");
        }
        assertThat(json.toString()).doesNotContain("SENTINELA_");
    }

    @Test
    void mascaraSegredosEPiiDentroDaMensagem() throws Exception {
        String jwt = "eyJhbGciOiJub25lIn0.eyJzdWIiOiJ4In0.assinaturaficticia";
        String texto = "falha Bearer abc.def-123 jwt " + jwt + " password=SENTINELA_PW token: SENTINELA_TK"
                + " email fulano@example.invalid cpf 123.456.789-09 cpf2 12345678909 tel (11) 91234-5678"
                + " access_token=SENTINELA_AT cpf=SENTINELA_CPF2 mp APP_USR-0123456789-ficticio-abcdef";

        String mensagem = emitir(texto).get("message").asText();

        assertThat(mensagem).doesNotContain("abc.def-123", jwt, "SENTINELA_PW", "SENTINELA_TK", "SENTINELA_AT", "SENTINELA_CPF2", "fulano@example.invalid",
                "123.456.789-09", "12345678909", "91234-5678", "APP_USR-0123456789");
        assertThat(mensagem).contains("[REDACTED]").startsWith("falha ");
    }

    @Test
    void mascaraCredenciaisBasicJsonEmbutidoEPrefixosComoDbPassword() throws Exception {
        String texto = "Authorization: Basic SENTINELA_B64 corpo {\"password\":\"SENTINELA_P1\",\"accessToken\":\"SENTINELA_P2\"}"
                + " DB_PASSWORD=SENTINELA_P3 aws_secret_access_key=SENTINELA_P4 chave " + CHAVE_AWS_FICTICIA;

        String mensagem = emitir(texto).get("message").asText();

        assertThat(mensagem).doesNotContain("SENTINELA_", CHAVE_AWS_FICTICIA);
        assertThat(mensagem).contains("[REDACTED]");
    }

    @Test
    void mascaraVariantesEstruturadasDeToken() throws Exception {
        JsonNode json = emitir("campos {} {} {} {} {}",
                StructuredArguments.kv("accessToken", "SENTINELA_A"),
                StructuredArguments.kv("refreshToken", "SENTINELA_R"),
                StructuredArguments.kv("idToken", "SENTINELA_I"),
                StructuredArguments.kv("x-api-key", "SENTINELA_K"),
                StructuredArguments.kv("jwt", "SENTINELA_J"));

        assertThat(json.toString()).doesNotContain("SENTINELA_");
    }

    @Test
    void mensagemLongaNaoTravaOMascaramento() {
        String longa = "a".repeat(200_000) + "@" + "b".repeat(50_000) + " " + "eyJ" + "x".repeat(100_000);
        assertThat(org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                () -> emitir(longa).get("message").asText())).isNotBlank();
    }

    @Test
    void naoMascaraUuidNemMensagemEstatica() throws Exception {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        assertThat(emitir("Comando duplicado ignorado " + uuid).get("message").asText())
                .isEqualTo("Comando duplicado ignorado " + uuid);
    }

    @Test
    void naoEscreveStackTrace() throws Exception {
        context.getLogger("teste").warn("erro", new IllegalStateException("SENTINELA_EXCECAO"));
        String saida = captured.toString(StandardCharsets.UTF_8);
        assertThat(JSON.readTree(saida.trim()).has("stack_trace")).isFalse();
        assertThat(saida).doesNotContain("SENTINELA_EXCECAO", "IllegalStateException");
    }
}
