package com.oficina.execucao.config;

import com.oficina.execucao.config.ExecucaoProperties.Modo;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Monta os clientes reais: NODE_ROLE usa a default chain sem endpoint; LOCALSTACK segue com credenciais dummy. */
class ClientesAwsNodeRoleTest {
    private static ExecucaoProperties props(Modo modo, String endpoint) {
        return new ExecucaoProperties(modo, List.of("MATRIZ"), new ExecucaoProperties.Capacidade(5),
                new ExecucaoProperties.Mensageria(false, "us-east-1", endpoint, "q", "r"),
                new ExecucaoProperties.Dynamodb("us-east-1", endpoint, "e", "o", "i", false));
    }

    @Test void nodeRoleMontaClientesComDefaultChainSemEndpoint() {
        try (ClientesAws clientes = ClientesAws.criar(props(Modo.NODE_ROLE, ""), "", "", "")) {
            var configs = List.of(clientes.dynamo().serviceClientConfiguration(),
                    clientes.sqsEnvio().serviceClientConfiguration(), clientes.sqsRecepcao().serviceClientConfiguration());
            for (var config : configs) {
                assertThat(config.endpointOverride()).isEmpty();
                assertThat(config.credentialsProvider()).isInstanceOf(DefaultCredentialsProvider.class);
            }
        }
    }

    @Test void nodeRoleComChaveEstaticaNaoMontaClientes() {
        assertThatThrownBy(() -> ClientesAws.criar(props(Modo.NODE_ROLE, ""), "", "", "AKIASEGREDO"))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("AWS_ACCESS_KEY_ID")
                .message().doesNotContain("AKIASEGREDO");
    }

    @Test void localstackContinuaComCredenciaisDummyEEndpoint() {
        try (ClientesAws clientes = ClientesAws.criar(props(Modo.LOCALSTACK, "http://localhost:4566"), "", "", "")) {
            var config = clientes.sqsEnvio().serviceClientConfiguration();
            assertThat(config.endpointOverride()).isPresent();
            assertThat(config.credentialsProvider()).isInstanceOf(StaticCredentialsProvider.class);
        }
    }
}
