package com.oficina.execucao;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;

import java.util.Map;

@SpringBootTest(classes = ExecutionApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ExecutionApplicationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void healthEndpointIsAvailableUnderExecutionContext() {
        var response = restTemplate.getForEntity(
                "http://localhost:" + port + "/execucao/actuator/health", Map.class);

        Assertions.assertEquals(HttpStatus.OK, response.getStatusCode());
        Assertions.assertEquals("UP", response.getBody().get("status"));
    }
}
