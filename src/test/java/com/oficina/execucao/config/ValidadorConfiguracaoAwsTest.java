package com.oficina.execucao.config;

import com.oficina.execucao.config.ExecucaoProperties.Modo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ValidadorConfiguracaoAwsTest {
    private static final String ROLE = "arn:aws:iam::123456789012:role/execution-irsa";
    private static final String LOCAL = "http://localhost:4566";

    @TempDir Path dir;

    private static AmbienteAws ambiente(Map<String, String> variaveis, Map<String, String> propriedadesJvm) {
        return AmbienteAws.de(variaveis, propriedadesJvm);
    }

    private static void validarLimpo(ExecucaoProperties p, String roleArn, String tokenFile) {
        ValidadorConfiguracaoAws.validar(p, roleArn, tokenFile, ambiente(Map.of(), Map.of()));
    }

    private static ExecucaoProperties props(Modo modo, String dynamo, String sqs, boolean criar) {
        return new ExecucaoProperties(modo, List.of("MATRIZ"), new ExecucaoProperties.Capacidade(5),
                new ExecucaoProperties.Mensageria(false, "us-east-1", sqs, "q", "r"),
                new ExecucaoProperties.Dynamodb("us-east-1", dynamo, "e", "o", "i", criar));
    }

    private String token(String conteudo) throws Exception {
        return Files.writeString(dir.resolve("token"), conteudo).toString();
    }

    @Test void modoAusenteAbortaStartup() {
        assertThatThrownBy(() -> validarLimpo(props(null, LOCAL, LOCAL, true), "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("execucao.modo");
    }

    @Test void localstackComAmbosEndpointsPassa() {
        assertThatCode(() -> validarLimpo(props(Modo.LOCALSTACK, LOCAL, LOCAL, true), "", ""))
                .doesNotThrowAnyException();
    }

    @Test void localstackComEndpointParcialAborta() {
        assertThatThrownBy(() -> validarLimpo(props(Modo.LOCALSTACK, LOCAL, "", true), "", ""))
                .hasMessageContaining("execucao.mensageria.endpoint");
        assertThatThrownBy(() -> validarLimpo(props(Modo.LOCALSTACK, null, LOCAL, true), "", ""))
                .hasMessageContaining("execucao.dynamodb.endpoint");
    }

    @Test void localstackAceitaHostsPermitidos() {
        for (String host : List.of("http://127.0.0.1:4566", "http://localhost:4566", "http://[::1]:4566",
                "http://localstack.infra.svc:4566")) {
            assertThatCode(() -> validarLimpo(props(Modo.LOCALSTACK, host, host, true), "", ""))
                    .doesNotThrowAnyException();
        }
    }

    @Test void localstackRejeitaHostForaDaAllowlist() {
        for (String host : List.of("https://dynamodb.us-east-1.amazonaws.com", "http://sqs.us-east-1.amazonaws.com",
                "http://localhost.evil.com:4566", "not a uri", "localhost:4566", "//localhost:4566", " http://localhost:4566",
                "ftp://localhost:4566", "http://localhost@evil.com", "http://localhost:4566@evil.com",
                "http://evil.com#@localhost", "http://localhost.", "http://[::ffff:127.0.0.1]:4566")) {
            assertThatThrownBy(() -> validarLimpo(props(Modo.LOCALSTACK, host, LOCAL, true), "", ""))
                    .hasMessageContaining("execucao.dynamodb.endpoint deve apontar");
            assertThatThrownBy(() -> validarLimpo(props(Modo.LOCALSTACK, LOCAL, host, true), "", ""))
                    .hasMessageContaining("execucao.mensageria.endpoint deve apontar");
        }
    }

    @Test void irsaValidoPassa() throws Exception {
        String t = token("segredo");
        assertThatCode(() -> validarLimpo(props(Modo.IRSA, "", "", false), ROLE, t))
                .doesNotThrowAnyException();
    }

    @Test void irsaRejeitaEndpointLocal() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, LOCAL, "", false), ROLE, t))
                .hasMessageContaining("execucao.dynamodb.endpoint");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, "", LOCAL, false), ROLE, t))
                .hasMessageContaining("execucao.mensageria.endpoint");
    }

    @Test void irsaRejeitaCriarTabelas() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, "", "", true), ROLE, t))
                .hasMessageContaining("criar-tabelas");
    }

    @Test void irsaRejeitaRoleInvalida() throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, "", "", false), "", t))
                .hasMessageContaining("AWS_ROLE_ARN");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, "", "", false), "role-qualquer", t))
                .hasMessageContaining("AWS_ROLE_ARN");
    }

    @Test void irsaRejeitaTokenInvalido() throws Exception {
        var p = props(Modo.IRSA, "", "", false);
        assertThatThrownBy(() -> validarLimpo(p, ROLE, ""))
                .hasMessageContaining("AWS_WEB_IDENTITY_TOKEN_FILE ausente");
        assertThatThrownBy(() -> validarLimpo(p, ROLE, dir.resolve("nao-existe").toString()))
                .hasMessageContaining("nao e um arquivo existente");
        assertThatThrownBy(() -> validarLimpo(p, ROLE, dir.toString()))
                .hasMessageContaining("nao e um arquivo existente");
        String vazio = token("");
        assertThatThrownBy(() -> validarLimpo(p, ROLE, vazio)).hasMessageContaining("vazio");
    }

    @Test void mensagemNuncaContemConteudoDoToken() throws Exception {
        String t = token("SEGREDO-JWT");
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, LOCAL, "", false), "ruim", t))
                .message().doesNotContain("SEGREDO-JWT");
    }

    @Test void nodeRoleValidoPassaSemRoleNemToken() {
        assertThatCode(() -> validarLimpo(props(Modo.NODE_ROLE, "", "", false), "", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> validarLimpo(props(Modo.NODE_ROLE, null, null, false), null, null))
                .doesNotThrowAnyException();
    }

    @Test void nodeRoleRejeitaEndpointOverride() {
        assertThatThrownBy(() -> validarLimpo(props(Modo.NODE_ROLE, LOCAL, "", false), "", ""))
                .hasMessageContaining("execucao.dynamodb.endpoint");
        assertThatThrownBy(() -> validarLimpo(props(Modo.NODE_ROLE, "", LOCAL, false), "", ""))
                .hasMessageContaining("execucao.mensageria.endpoint");
    }

    @Test void nodeRoleRejeitaCriarTabelas() {
        assertThatThrownBy(() -> validarLimpo(props(Modo.NODE_ROLE, "", "", true), "", ""))
                .hasMessageContaining("criar-tabelas");
    }

    @Test void nodeRoleRejeitaChaveEstaticaSemVazarValor() {
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), "", "", ambiente(Map.of("AWS_ACCESS_KEY_ID", "AKIASEGREDO"), Map.of())))
                .hasMessageContaining("AWS_ACCESS_KEY_ID").message().doesNotContain("AKIASEGREDO");
    }

    @Test void irsaELocalstackContinuamIndependentesDaChaveEstatica() throws Exception {
        String t = token("x");
        assertThatCode(() -> validarLimpo(props(Modo.IRSA, "", "", false), ROLE, t))
                .doesNotThrowAnyException();
        assertThatCode(() -> validarLimpo(props(Modo.LOCALSTACK, LOCAL, LOCAL, true), "", ""))
                .doesNotThrowAnyException();
    }

    @Test void irsaAindaExigeRoleETokenAoLadoDoNodeRole() {
        assertThatThrownBy(() -> validarLimpo(props(Modo.IRSA, "", "", false), "", ""))
                .hasMessageContaining("AWS_ROLE_ARN").hasMessageContaining("AWS_WEB_IDENTITY_TOKEN_FILE");
    }

    private static void validarNodeRole(Map<String, String> variaveis, Map<String, String> propriedadesJvm) {
        ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), "", "", ambiente(variaveis, propriedadesJvm));
    }

    @Test void nodeRoleRejeitaEndpointsDefinidosForaDasPropertiesSpring() {
        for (String nome : List.of("AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_SQS", "AWS_ENDPOINT_URL_DYNAMODB")) {
            assertThatThrownBy(() -> validarNodeRole(Map.of(nome, "http://evil.example:4566"), Map.of()))
                    .hasMessageContaining(nome).message().doesNotContain("evil.example");
        }
        for (String nome : List.of("aws.endpointUrl", "aws.endpointUrlSqs", "aws.endpointUrlDynamodb")) {
            assertThatThrownBy(() -> validarNodeRole(Map.of(), Map.of(nome, "http://evil.example:4566")))
                    .hasMessageContaining(nome).message().doesNotContain("evil.example");
        }
    }

    @Test void nodeRoleRejeitaFontesDeCredencialAnterioresAoImds() {
        List<String> variaveis = List.of("AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
                "AWS_WEB_IDENTITY_TOKEN_FILE", "AWS_PROFILE", "AWS_SHARED_CREDENTIALS_FILE",
                "AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "AWS_CONTAINER_CREDENTIALS_FULL_URI",
                "AWS_CONTAINER_AUTHORIZATION_TOKEN", "AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");
        for (String nome : variaveis) {
            assertThatThrownBy(() -> validarNodeRole(Map.of(nome, "VALOR-SEGREDO"), Map.of()))
                    .hasMessageContaining(nome).message().doesNotContain("VALOR-SEGREDO");
        }
        for (String nome : List.of("aws.accessKeyId", "aws.secretAccessKey", "aws.sessionToken",
                "aws.webIdentityTokenFile", "aws.profile", "aws.sharedCredentialsFile")) {
            assertThatThrownBy(() -> validarNodeRole(Map.of(), Map.of(nome, "VALOR-SEGREDO")))
                    .hasMessageContaining(nome).message().doesNotContain("VALOR-SEGREDO");
        }
    }

    @Test void nodeRoleRejeitaRoleArnETokenDeWebIdentityRecebidosPorParametro() {
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), ROLE, "",
                ambiente(Map.of(), Map.of()))).hasMessageContaining("AWS_ROLE_ARN").message().doesNotContain(ROLE);
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), "", "/var/run/token",
                ambiente(Map.of(), Map.of()))).hasMessageContaining("AWS_WEB_IDENTITY_TOKEN_FILE")
                .message().doesNotContain("/var/run/token");
    }

    @Test void nodeRoleRejeitaImdsDesabilitado() {
        assertThatThrownBy(() -> validarNodeRole(Map.of("AWS_EC2_METADATA_DISABLED", "true"), Map.of()))
                .hasMessageContaining("AWS_EC2_METADATA_DISABLED");
        assertThatCode(() -> validarNodeRole(Map.of("AWS_EC2_METADATA_DISABLED", "false", "AWS_REGION", "us-east-1"), Map.of()))
                .doesNotThrowAnyException();
    }

    @Test void nodeRoleIgnoraValoresEmBranco() {
        assertThatCode(() -> validarNodeRole(Map.of("AWS_ACCESS_KEY_ID", " "), Map.of("aws.profile", "")))
                .doesNotThrowAnyException();
    }

    @Test void localstackIgnoraFontesDoAmbiente() {
        var ambiente = ambiente(Map.of("AWS_ACCESS_KEY_ID", "test", "AWS_ENDPOINT_URL", LOCAL,
                "AWS_EC2_METADATA_DISABLED", "true"), Map.of("aws.configFile", "/x"));
        assertThatCode(() -> ValidadorConfiguracaoAws.validar(props(Modo.LOCALSTACK, LOCAL, LOCAL, true), "", "", ambiente))
                .doesNotThrowAnyException();
    }

    @Test void irsaIgnoraChaveEstaticaMasValidaComAmbienteLimpo() throws Exception {
        String t = token("x");
        var ambiente = ambiente(Map.of("AWS_ACCESS_KEY_ID", "test"), Map.of());
        assertThatCode(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t, ambiente))
                .doesNotThrowAnyException();
    }

    // E1: IRSA nao aceita endpoint redirecionado por env/JVM
    @ParameterizedTest @ValueSource(strings = {"AWS_ENDPOINT_URL", "AWS_ENDPOINT_URL_SQS", "AWS_ENDPOINT_URL_DYNAMODB",
            "AWS_ENDPOINT_URL_STS"})
    void irsaRejeitaEndpointPorVariavel(String nome) throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t,
                ambiente(Map.of(nome, "http://evil.example:4566"), Map.of())))
                .hasMessageContaining(nome).message().doesNotContain("evil.example");
    }

    @ParameterizedTest @ValueSource(strings = {"aws.endpointUrl", "aws.endpointUrlSqs", "aws.endpointUrlDynamodb",
            "aws.endpointUrlSts"})
    void irsaRejeitaEndpointPorPropriedadeJvm(String nome) throws Exception {
        String t = token("x");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t,
                ambiente(Map.of(), Map.of(nome, "http://evil.example:4566"))))
                .hasMessageContaining(nome).message().doesNotContain("evil.example");
    }

    // E2: NODE_ROLE nao aceita fontes de container/role por JVM nem endpoint de container
    @ParameterizedTest @ValueSource(strings = {"AWS_CONTAINER_SERVICE_ENDPOINT", "AWS_EC2_METADATA_SERVICE_ENDPOINT"})
    void nodeRoleRejeitaEndpointsDeCredencialPorVariavel(String nome) {
        assertThatThrownBy(() -> validarNodeRole(Map.of(nome, "http://evil.example"), Map.of()))
                .hasMessageContaining(nome).message().doesNotContain("evil.example");
    }

    @ParameterizedTest @ValueSource(strings = {"aws.containerCredentialsPath", "aws.containerCredentialsFullUri",
            "aws.containerAuthorizationToken", "aws.containerAuthorizationTokenFile", "aws.roleArn",
            "aws.containerServiceEndpoint", "aws.configFile", "aws.ec2MetadataServiceEndpoint"})
    void nodeRoleRejeitaFontesProibidasPorPropriedadeJvm(String nome) {
        assertThatThrownBy(() -> validarNodeRole(Map.of(), Map.of(nome, "VALOR-SEGREDO")))
                .hasMessageContaining(nome).message().doesNotContain("VALOR-SEGREDO");
    }

    // E3: AWS_CONFIG_FILE e perfil default compartilhado
    @Test void nodeRoleRejeitaAwsConfigFile() {
        assertThatThrownBy(() -> validarNodeRole(Map.of("AWS_CONFIG_FILE", "/tmp/segredo-config"), Map.of()))
                .hasMessageContaining("AWS_CONFIG_FILE").message().doesNotContain("segredo-config");
    }

    private void escrever(String arquivo, String conteudo) throws Exception {
        Path alvo = dir.resolve(".aws").resolve(arquivo);
        Files.createDirectories(alvo.getParent());
        Files.writeString(alvo, conteudo);
    }

    private void validarNodeRoleComHome() {
        ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), "", "",
                AmbienteAws.de(Map.of(), Map.of(), dir));
    }

    @ParameterizedTest @ValueSource(strings = {"aws_access_key_id", "aws_secret_access_key", "aws_session_token",
            "credential_process", "web_identity_token_file", "role_arn", "source_profile", "sso_session"})
    void nodeRoleRejeitaPerfilDefaultDeCredenciaisCompartilhadas(String chave) throws Exception {
        escrever("credentials", "[other]\nx=1\n\n[default]\n" + chave + " = VALOR-SEGREDO\n");
        assertThatThrownBy(this::validarNodeRoleComHome)
                .hasMessageContaining(".aws/credentials").hasMessageContaining(chave)
                .message().doesNotContain("VALOR-SEGREDO");
    }

    @ParameterizedTest @ValueSource(strings = {"[default]", "[profile default]", "[ profile   default ]"})
    void nodeRoleRejeitaPerfilDefaultDoConfig(String secao) throws Exception {
        escrever("config", "# c\n" + secao + "\nregion = us-east-1\nendpoint_url = http://evil.example\n");
        assertThatThrownBy(this::validarNodeRoleComHome)
                .hasMessageContaining(".aws/config").hasMessageContaining("endpoint_url")
                .message().doesNotContain("evil.example");
    }

    @Test void nodeRoleRejeitaImdsRedirecionadoNoConfig() throws Exception {
        escrever("config", "[default]\nec2_metadata_service_endpoint = http://evil.example\n");
        assertThatThrownBy(this::validarNodeRoleComHome).hasMessageContaining("ec2_metadata_service_endpoint");
    }

    @Test void nodeRoleAceitaArquivosCompartilhadosSemInterferencia() throws Exception {
        escrever("credentials", "[ci]\naws_access_key_id = X\naws_secret_access_key = Y\n");
        escrever("config", "[default]\nregion = us-east-1\noutput = json\n[profile ci]\nendpoint_url = http://x\n");
        assertThatCode(this::validarNodeRoleComHome).doesNotThrowAnyException();
    }

    @Test void nodeRoleFalhaFechadoQuandoArquivoCompartilhadoNaoELegivel() throws Exception {
        Files.createDirectories(dir.resolve(".aws/credentials")); // diretorio no lugar do arquivo
        assertThatThrownBy(this::validarNodeRoleComHome).hasMessageContaining(".aws/credentials");
    }

    // E4: IMDS desabilitado ou redirecionado
    @ParameterizedTest @ValueSource(strings = {"true", "TRUE", " true "})
    void nodeRoleRejeitaImdsDesabilitadoPorPropriedadeJvm(String valor) {
        assertThatThrownBy(() -> validarNodeRole(Map.of(), Map.of("aws.disableEc2Metadata", valor)))
                .hasMessageContaining("aws.disableEc2Metadata");
        assertThatCode(() -> validarNodeRole(Map.of(), Map.of("aws.disableEc2Metadata", "false")))
                .doesNotThrowAnyException();
    }

    @Test void irsaNaoExigeImdsNemPerfilCompartilhado() throws Exception {
        String t = token("x");
        escrever("credentials", "[default]\naws_access_key_id = X\n");
        assertThatCode(() -> ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t,
                AmbienteAws.de(Map.of("AWS_EC2_METADATA_DISABLED", "true"), Map.of(), dir)))
                .doesNotThrowAnyException();
    }

    // UNR-882 F1: IRSA nao aceita endpoint vindo do arquivo AWS compartilhado
    private void validarIrsaComHome(Map<String, String> variaveis, Map<String, String> jvm, Path home) throws Exception {
        String t = token("x");
        ValidadorConfiguracaoAws.validar(props(Modo.IRSA, "", "", false), ROLE, t, AmbienteAws.de(variaveis, jvm, home));
    }

    @ParameterizedTest @ValueSource(strings = {"AWS_CONFIG_FILE", "AWS_SHARED_CREDENTIALS_FILE"})
    void irsaRejeitaArquivoAlternativoPorVariavel(String nome) {
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(nome, "/tmp/segredo-arquivo"), Map.of(), dir))
                .hasMessageContaining(nome).message().doesNotContain("segredo-arquivo");
    }

    @ParameterizedTest @ValueSource(strings = {"aws.configFile", "aws.sharedCredentialsFile"})
    void irsaRejeitaArquivoAlternativoPorPropriedadeJvm(String nome) {
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(), Map.of(nome, "/tmp/segredo-arquivo"), dir))
                .hasMessageContaining(nome).message().doesNotContain("segredo-arquivo");
    }

    @ParameterizedTest @ValueSource(strings = {"endpoint_url", "services"})
    void irsaRejeitaEndpointNoPerfilDefaultDoConfig(String chave) throws Exception {
        escrever("config", "[default]\nregion = us-east-1\n" + chave + " = evil.example\n");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(), Map.of(), dir))
                .hasMessageContaining(".aws/config").hasMessageContaining(chave)
                .message().doesNotContain("evil.example");
    }

    @Test void irsaRejeitaEndpointNoPerfilEfetivoEscolhidoPorAwsProfile() throws Exception {
        escrever("config", "[profile alvo]\nendpoint_url = http://evil.example\n");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of("AWS_PROFILE", "alvo"), Map.of(), dir))
                .hasMessageContaining("endpoint_url").message().doesNotContain("evil.example");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(), Map.of("aws.profile", "alvo"), dir))
                .hasMessageContaining("endpoint_url");
    }

    @Test void irsaRejeitaEndpointNoPerfilEfetivoDoArquivoDeCredenciais() throws Exception {
        escrever("credentials", "[default]\nendpoint_url = http://evil.example\n");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(), Map.of(), dir))
                .hasMessageContaining(".aws/credentials").hasMessageContaining("endpoint_url");
    }

    @Test void irsaIgnoraEndpointEmPerfilQueNaoEEfetivo() throws Exception {
        escrever("config", "[default]\nregion = us-east-1\n[profile outro]\nendpoint_url = http://x\n");
        assertThatCode(() -> validarIrsaComHome(Map.of(), Map.of(), dir)).doesNotThrowAnyException();
    }

    @Test void irsaFalhaFechadoQuandoArquivoCompartilhadoNaoELegivel() throws Exception {
        Files.createDirectories(dir.resolve(".aws/config"));
        assertThatThrownBy(() -> validarIrsaComHome(Map.of(), Map.of(), dir)).hasMessageContaining(".aws/config");
    }

    // UNR-882 F2: o SDK resolve o home por env HOME antes de user.home
    @Test void envHomeDivergenteDeUserHomeEOUsadoNoNodeRole(@TempDir Path outroHome) throws Exception {
        Files.createDirectories(outroHome.resolve(".aws"));
        Files.writeString(outroHome.resolve(".aws/config"), "[default]\nendpoint_url = http://evil.example\n");
        assertThatThrownBy(() -> ValidadorConfiguracaoAws.validar(props(Modo.NODE_ROLE, "", "", false), "", "",
                AmbienteAws.de(Map.of("HOME", outroHome.toString()), Map.of(), dir)))
                .hasMessageContaining("endpoint_url").message().doesNotContain("evil.example");
    }

    @Test void envHomeDivergenteDeUserHomeEOUsadoNoIrsa(@TempDir Path outroHome) throws Exception {
        Files.createDirectories(outroHome.resolve(".aws"));
        Files.writeString(outroHome.resolve(".aws/config"), "[default]\nservices = x\n");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of("HOME", outroHome.toString()), Map.of(), dir))
                .hasMessageContaining("services");
    }

    @Test void homeEmBrancoCaiNoUserHome() throws Exception {
        escrever("config", "[default]\nendpoint_url = http://evil.example\n");
        assertThatThrownBy(() -> validarIrsaComHome(Map.of("HOME", " "), Map.of(), dir))
                .hasMessageContaining("endpoint_url");
    }
}
