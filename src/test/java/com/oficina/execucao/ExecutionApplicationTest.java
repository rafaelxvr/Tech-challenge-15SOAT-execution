package com.oficina.execucao;

import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ExecutionApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionApplicationTest extends LocalStackSupport {
    @Autowired TestRestTemplate http;
    @Autowired RegistroExecucoes registro;

    @Test void healthRespondeUpSobContextPath() {
        assertThat(http.getRestTemplate().getUriTemplateHandler().expand("/x").getPath()).isEqualTo("/execucao/x");
        var resposta = http.getForEntity("/actuator/health", String.class);
        assertThat(resposta.getStatusCode().value()).isEqualTo(200);
        assertThat(resposta.getBody()).contains("\"status\":\"UP\"");
    }

    @Test void tabelasLocaisCriadasERegistroResponde() {
        assertThat(registro.porOs(UUID.randomUUID())).isEmpty();
    }
}
