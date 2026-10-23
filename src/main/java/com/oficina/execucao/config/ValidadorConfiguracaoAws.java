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

    static void validar(ExecucaoProperties p, String roleArn, String tokenFile) {
        if (p.modo() == null) {
            throw new IllegalStateException("execucao.modo e obrigatorio: use LOCALSTACK ou IRSA (EXECUCAO_AWS_MODO)");
        }
        List<String> erros = new ArrayList<>();
        switch (p.modo()) {
            case LOCALSTACK -> validarLocalstack(p, erros);
            case IRSA -> validarIrsa(p, roleArn, tokenFile, erros);
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
        String host;
        try {
            host = URI.create(endpoint.trim()).getHost();
        } catch (IllegalArgumentException e) {
            host = null;
        }
        if (host == null || !HOSTS_PERMITIDOS.contains(host.toLowerCase())) {
            erros.add(propriedade + " deve apontar para host local permitido (loopback ou localstack.infra.svc)");
        }
    }

    private static void validarIrsa(ExecucaoProperties p, String roleArn, String tokenFile, List<String> erros) {
        if (!vazio(p.dynamodb().endpoint())) erros.add("execucao.dynamodb.endpoint nao pode ser definido");
        if (!vazio(p.mensageria().endpoint())) erros.add("execucao.mensageria.endpoint nao pode ser definido");
        if (p.dynamodb().criarTabelas()) erros.add("execucao.dynamodb.criar-tabelas nao pode ser true (tabelas vem do Terraform)");
        if (vazio(roleArn) || !ROLE_ARN.matcher(roleArn).matches()) erros.add("AWS_ROLE_ARN ausente ou invalido");
        String problemaToken = problemaDoToken(tokenFile);
        if (problemaToken != null) erros.add(problemaToken);
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
