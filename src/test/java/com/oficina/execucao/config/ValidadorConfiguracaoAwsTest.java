package com.oficina.execucao.config;

import com.oficina.execucao.config.ExecucaoProperties.Modo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorConfiguracaoAwsTest {
    private static final String ROLE = "arn:aws:iam::123456789012:role/execution-irsa";
    private static final String LOCAL = "http://localhost:4566";

    @TempDir Path dir;

    private static ExecucaoProperties props(Modo modo, String dynamo, String sqs, boolean criar) {
        return new ExecucaoProperties(modo, List.of("MATRIZ"), new ExecucaoProperties.Capacidade(5),
                new ExecucaoProperties.Mensageria(false, "us-east-1", sqs, "q", "r"),
                new ExecucaoProperties.Dynamodb("us-east-1", dynamo, "e", "o", "i", criar));
    }

    private String token(String conteudo) throws Exception {
        return Files.writeString(dir.resolve("token"), conteudo).toString();
    }

    @Test void modoAusenteAbortaStartup() {
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(null, LOCAL, LOCAL, true), "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("execucao.modo");
    }

    @Test void localstackComAmbosEndpointsPassa() {
        assertThatCode(() -> ValidadorConfiguracaoAws.validar(props(Modo.LOCALSTACK, LOCAL, LOCAL, true), "", ""))
                .doesNotThrowAnyException();
    }

    @Test void localstackComEndpointParcialAborta() {
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.LOCALSTACK, LOCAL, "", true), "", ""))
                .hasMessageContaining("execucao.mensageria.endpoint");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.LOCALSTACK, null, LOCAL, true), "", ""))
                .hasMessageContaining("execucao.dynamodb.endpoint");
    }

    @Test void irsaValidoPassa() throws Exception {
        String t = token("segredo");
        assertThatCode(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t))
                .doesNotThrowAnyException();
    }

    @Test void irsaRejeitaEndpointLocal() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, LOCAL, "", false), ROLE, t))
                .hasMessageContaining("execucao.dynamodb.endpoint");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", LOCAL, false), ROLE, t))
                .hasMessageContaining("execucao.mensageria.endpoint");
    }

    @Test void irsaRejeitaCriarTabelas() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", true), ROLE, t))
                .hasMessageContaining("criar-tabelas");
    }

    @Test void irsaRejeitaRoleInvalida() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), "", t))
                .hasMessageContaining("AWS_ROLE_ARN");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), "role-qualquer", t))
                .hasMessageContaining("AWS_ROLE_ARN");
    }

    @Test void irsaRejeitaTokenInvalido() throws Exception {
        var p = props(Modo.IRSA, "", "", false);
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(p, ROLE, ""))
                .hasMessageContaining("AWS_WEB_IDENTITY_TOKEN_FILE ausente");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(p, ROLE, dir.resolve("nao-existe").toString()))
                .hasMessageContaining("nao e um arquivo existente");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(p, ROLE, dir.toString()))
                .hasMessageContaining("nao e um arquivo existente");
        String vazio = token("");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(p, ROLE, vazio)).hasMessageContaining("vazio");
    }

    @Test void mensagemNuncaContemConteudoDoToken() throws Exception {
        String t = token("SEGREDO-JWT");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, LOCAL, "", false), "ruim", t))
                .message().doesNotContain("SEGREDO-JWT");
    }
}
