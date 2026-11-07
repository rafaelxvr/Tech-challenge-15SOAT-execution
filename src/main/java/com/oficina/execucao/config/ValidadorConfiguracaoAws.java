package com.oficina.execucao.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fails fast when the AWS wiring could leave the intended environment: LOCALSTACK needs both local endpoints,
 * IRSA forbids them, needs a valid role and a readable token file, and never creates tables.
 * Messages never include the token content.
 */
final class ValidadorConfiguracaoAws {
    private static final Pattern ROLE_ARN = Pattern.compile("^arn:aws[a-z-]*:iam::\\d{12}:role/[\\w+=,.@/-]+$");

    /** S-3: the only hosts a local/Kind endpoint may point at: loopback (host tests) or LocalStack inside the cluster. */
    private static final Set<String> HOSTS_PERMITIDOS = Set.of("localhost", "127.0.0.1", "[::1]", "localstack.infra.svc");

    private ValidadorConfiguracaoAws() {}

    static void validar(ExecucaoProperties p, String roleArn, String tokenFile, AmbienteAws ambiente) {
        if (p.modo() == null) {
            throw new IllegalStateException("execucao.modo e obrigatorio: use LOCALSTACK, IRSA ou NODE_ROLE (EXECUCAO_AWS_MODO)");
        }
        List<String> erros = new ArrayList<>();
        switch (p.modo()) {
            case LOCALSTACK -> validarLocalstack(p, erros);
            case IRSA -> validarIrsa(p, roleArn, tokenFile, ambiente, erros);
            case NODE_ROLE -> validarNodeRole(p, roleArn, tokenFile, ambiente, erros);
        }
        if (!erros.isEmpty()) {
            throw new IllegalStateException("Configuracao AWS invalida no modo " + p.modo() + ": " + String.join("; ", erros));
        }
    }

    private static void validarLocalstack(ExecucaoProperties p, List<String> erros) {
        validarEndpointLocal("execucao.dynamodb.endpoint", p.dynamodb().endpoint(), erros);
        validarEndpointLocal("execucao.mensageria.endpoint", p.mensageria().endpoint(), erros);
    }

    private static void validarEndpointLocal(String propriedade, String endpoint, List<String> erros) {
        if (vazio(endpoint)) {
            erros.add(propriedade + " e obrigatorio");
            return;
        }
        if (!hostLocalPermitido(endpoint)) {
            erros.add(propriedade + " deve apontar para host local permitido (loopback ou localstack.infra.svc)");
        }
    }

    /** Same parser the SDK client receives (no trim): needs http/https scheme, no userinfo and an allowlisted host. */
    private static boolean hostLocalPermitido(String endpoint) {
        try {
            URI uri = URI.create(endpoint);
            String esquema = uri.getScheme();
            boolean esquemaValido = "http".equalsIgnoreCase(esquema) || "https".equalsIgnoreCase(esquema);
            return esquemaValido
                    && uri.getUserInfo() == null
                    && uri.getHost() != null
                    && HOSTS_PERMITIDOS.contains(uri.getHost().toLowerCase());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static void validarIrsa(ExecucaoProperties p, String roleArn, String tokenFile, AmbienteAws ambiente,
                                    List<String> erros) {
        validarSemEndpointNemTabelas(p, erros);
        rejeitarFontes(ENDPOINTS_DO_SDK, ambiente, erros);
        rejeitarFontes(ARQUIVOS_COMPARTILHADOS, ambiente, erros);
        rejeitarEndpointNoPerfilEfetivo(".aws/credentials", ambiente.chavesDoPerfilEfetivoEmCredenciais(), erros);
        rejeitarEndpointNoPerfilEfetivo(".aws/config", ambiente.chavesDoPerfilEfetivoEmConfig(), erros);
        if (vazio(roleArn) || !ROLE_ARN.matcher(roleArn).matches()) erros.add("AWS_ROLE_ARN ausente ou invalido");
        String problemaToken = problemaDoToken(tokenFile);
        if (problemaToken != null) erros.add(problemaToken);
    }

    /**
     * NODE_ROLE must end at the instance profile: besides the Spring endpoints, nothing the SDK reads on its own
     * (env, JVM properties, shared default profile) may redirect an endpoint, redirect or disable IMDS, or feed
     * credentials ahead of IMDS in the default chain. Messages carry names only, never values.
     */
    private static void validarNodeRole(ExecucaoProperties p, String roleArn, String tokenFile, AmbienteAws ambiente,
                                        List<String> erros) {
        validarSemEndpointNemTabelas(p, erros);
        rejeitarFontes(ENDPOINTS_DO_SDK, ambiente, erros);
        rejeitarFontes(FONTES_PROIBIDAS_NODE_ROLE, ambiente, erros);
        if (!vazio(roleArn)) erros.add("AWS_ROLE_ARN nao pode ser definido (web identity/IRSA)");
        if (!vazio(tokenFile)) erros.add("AWS_WEB_IDENTITY_TOKEN_FILE nao pode ser definido (web identity/IRSA)");
        if ("true".equalsIgnoreCase(trim(ambiente.variavel("AWS_EC2_METADATA_DISABLED")))) {
            erros.add("AWS_EC2_METADATA_DISABLED=true impede a role do no");
        }
        if ("true".equalsIgnoreCase(trim(ambiente.propriedade("aws.disableEc2Metadata")))) {
            erros.add("aws.disableEc2Metadata=true impede a role do no");
        }
        rejeitarPerfilDefault(".aws/credentials", ambiente.chavesDoPerfilDefaultEmCredenciais(), erros);
        rejeitarPerfilDefault(".aws/config", ambiente.chavesDoPerfilDefaultEmConfig(), erros);
    }

    private static void rejeitarFontes(List<FonteProibida> fontes, AmbienteAws ambiente, List<String> erros) {
        for (FonteProibida fonte : fontes) {
            if (ambiente.definido(fonte.variavel(), fonte.propriedadeJvm())) erros.add(fonte.descricao());
        }
    }

    /** IRSA: the effective shared profile may not carry an endpoint (STS, SQS or DynamoDB would be diverted). */
    private static void rejeitarEndpointNoPerfilEfetivo(String arquivo, Set<String> chaves, List<String> erros) {
        if (chaves.contains(AmbienteAws.ARQUIVO_ILEGIVEL)) erros.add(arquivo + " existe mas nao pode ser lido");
        for (String chave : CHAVES_DE_ENDPOINT) {
            if (chaves.contains(chave)) erros.add(arquivo + " (perfil efetivo) nao pode definir " + chave + " (desvia endpoints)");
        }
    }

    private static void rejeitarPerfilDefault(String arquivo, Set<String> chaves, List<String> erros) {
        if (chaves.contains(AmbienteAws.ARQUIVO_ILEGIVEL)) {
            erros.add(arquivo + " existe mas nao pode ser lido");
        }
        for (String chave : CHAVES_PROIBIDAS_NO_PERFIL) {
            if (chaves.contains(chave)) erros.add(arquivo + " [default] nao pode definir " + chave + " (venceria a role do no)");
        }
    }

    private static String trim(String valor) { return valor == null ? null : valor.strip(); }

    private record FonteProibida(String variavel, String propriedadeJvm, String motivo) {
        String descricao() {
            String nome = variavel == null ? propriedadeJvm : variavel + (propriedadeJvm == null ? "" : "/" + propriedadeJvm);
            return nome + " nao pode ser definido (" + motivo + ")";
        }
    }

    /** Endpoint overrides the SDK reads from env/JVM; forbidden in IRSA and NODE_ROLE (only LOCALSTACK may redirect). */
    private static final List<FonteProibida> ENDPOINTS_DO_SDK = List.of(
            new FonteProibida("AWS_ENDPOINT_URL", "aws.endpointUrl", "endpoint override global"),
            new FonteProibida("AWS_ENDPOINT_URL_SQS", "aws.endpointUrlSqs", "endpoint override do SQS"),
            new FonteProibida("AWS_ENDPOINT_URL_DYNAMODB", "aws.endpointUrlDynamodb", "endpoint override do DynamoDB"),
            new FonteProibida("AWS_ENDPOINT_URL_STS", "aws.endpointUrlSts", "endpoint override do STS"));

    /** IRSA: alternative shared files could smuggle endpoint_url/services past the default-profile check. */
    private static final List<FonteProibida> ARQUIVOS_COMPARTILHADOS = List.of(
            new FonteProibida("AWS_CONFIG_FILE", "aws.configFile", "arquivo de configuracao alternativo"),
            new FonteProibida("AWS_SHARED_CREDENTIALS_FILE", "aws.sharedCredentialsFile", "arquivo de credenciais alternativo"));

    private static final List<String> CHAVES_DE_ENDPOINT = List.of("endpoint_url", "services");

    /** Env/JVM sources the SDK resolves before IMDS, or that redirect/replace IMDS itself. */
    private static final List<FonteProibida> FONTES_PROIBIDAS_NODE_ROLE = List.of(
            new FonteProibida("AWS_ACCESS_KEY_ID", "aws.accessKeyId", "chave estatica; use a role do no"),
            new FonteProibida("AWS_SECRET_ACCESS_KEY", "aws.secretAccessKey", "chave estatica; use a role do no"),
            new FonteProibida("AWS_SESSION_TOKEN", "aws.sessionToken", "credencial temporaria estatica"),
            new FonteProibida("AWS_WEB_IDENTITY_TOKEN_FILE", "aws.webIdentityTokenFile", "web identity/IRSA"),
            new FonteProibida(null, "aws.roleArn", "web identity/IRSA"),
            new FonteProibida("AWS_PROFILE", "aws.profile", "perfil de credenciais"),
            new FonteProibida("AWS_SHARED_CREDENTIALS_FILE", "aws.sharedCredentialsFile", "arquivo de credenciais"),
            new FonteProibida("AWS_CONFIG_FILE", "aws.configFile", "arquivo de configuracao"),
            new FonteProibida("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI", "aws.containerCredentialsPath", "credenciais de container"),
            new FonteProibida("AWS_CONTAINER_CREDENTIALS_FULL_URI", "aws.containerCredentialsFullUri", "credenciais de container"),
            new FonteProibida("AWS_CONTAINER_AUTHORIZATION_TOKEN", "aws.containerAuthorizationToken", "credenciais de container"),
            new FonteProibida("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE", "aws.containerAuthorizationTokenFile", "credenciais de container"),
            new FonteProibida("AWS_CONTAINER_SERVICE_ENDPOINT", "aws.containerServiceEndpoint", "endpoint de credenciais de container"),
            new FonteProibida("AWS_EC2_METADATA_SERVICE_ENDPOINT", "aws.ec2MetadataServiceEndpoint", "redireciona o IMDS"));

    /** Keys of the shared [default] profile that would win over IMDS or redirect endpoints/IMDS. */
    private static final List<String> CHAVES_PROIBIDAS_NO_PERFIL = List.of(
            "aws_access_key_id", "aws_secret_access_key", "aws_session_token", "credential_process",
            "web_identity_token_file", "role_arn", "source_profile", "credential_source", "sso_session",
            "sso_account_id", "sso_role_name", "sso_start_url", "endpoint_url", "services",
            "ec2_metadata_service_endpoint");

    private static void validarSemEndpointNemTabelas(ExecucaoProperties p, List<String> erros) {
        if (!vazio(p.dynamodb().endpoint())) erros.add("execucao.dynamodb.endpoint nao pode ser definido");
        if (!vazio(p.mensageria().endpoint())) erros.add("execucao.mensageria.endpoint nao pode ser definido");
        if (p.dynamodb().criarTabelas()) erros.add("execucao.dynamodb.criar-tabelas nao pode ser true (tabelas vem do Terraform)");
    }

    private static String problemaDoToken(String tokenFile) {
        if (vazio(tokenFile)) return "AWS_WEB_IDENTITY_TOKEN_FILE ausente";
        try {
            Path caminho = Path.of(tokenFile);
            if (!Files.isRegularFile(caminho)) return "AWS_WEB_IDENTITY_TOKEN_FILE nao e um arquivo existente";
            if (!Files.isReadable(caminho)) return "AWS_WEB_IDENTITY_TOKEN_FILE nao e legivel";
            if (Files.size(caminho) == 0) return "AWS_WEB_IDENTITY_TOKEN_FILE esta vazio";
            return null;
        } catch (InvalidPathException | IOException e) {
            return "AWS_WEB_IDENTITY_TOKEN_FILE invalido";
        }
    }

    private static boolean vazio(String valor) { return valor == null || valor.isBlank(); }
}
