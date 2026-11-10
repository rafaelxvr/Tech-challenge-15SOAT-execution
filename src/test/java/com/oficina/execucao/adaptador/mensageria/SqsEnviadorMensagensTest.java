package com.oficina.execucao.adaptador.mensageria;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SqsEnviadorMensagensTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final SqsClient sqs = mock(SqsClient.class);
    final SqsEnviadorMensagens enviador = new SqsEnviadorMensagens(sqs, "https://sqs.local/000/replies.fifo");

    @AfterEach void limpar() { MDC.remove("traceparent"); }

    private SendMessageRequest enviar() { return enviar(Map.of()); }

    private SendMessageRequest enviar(Map<String, String> traco) {
        when(sqs.sendMessage(any(SendMessageRequest.class))).thenReturn(SendMessageResponse.builder().messageId("m1").build());
        assertThat(enviador.enviar(UUID.randomUUID(), UUID.randomUUID(), "{}", traco)).isEqualTo("m1");
        var captor = ArgumentCaptor.forClass(SendMessageRequest.class);
        verify(sqs).sendMessage(captor.capture());
        return captor.getValue();
    }

    @Test void propagaTraceparentValidoComoAtributoDaMensagem() {
        MDC.put("traceparent", TRACEPARENT);
        var attr = enviar().messageAttributes().get(SqsEnviadorMensagens.ATRIBUTO_TRACEPARENT);
        assertThat(attr.dataType()).isEqualTo("String");
        assertThat(attr.stringValue()).isEqualTo(TRACEPARENT);
    }

    @Test void carrierPersistidoViraAtributosDaMensagemEPrevaleceSobreOMdc() {
        MDC.put("traceparent", "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01");
        var atributos = enviar(Map.of("traceparent", TRACEPARENT, "tracestate", "123@nr=0-0-1-2-3",
                "newrelic", "eyJ2IjpbMCwxXX0=", "email", "x@y.z")).messageAttributes();
        assertThat(atributos).containsOnlyKeys("traceparent", "tracestate", "newrelic");
        assertThat(atributos.get("traceparent").stringValue()).isEqualTo(TRACEPARENT);
        assertThat(atributos.get("tracestate").stringValue()).isEqualTo("123@nr=0-0-1-2-3");
    }

    @Test void naoEnviaAtributoQuandoTraceparentAusente() {
        assertThat(enviar().messageAttributes()).isEmpty();
    }

    @Test void descartaTraceparentMalformado() {
        MDC.put("traceparent", "00-malformado X-Injetado: 1");
        assertThat(enviar().messageAttributes()).isEmpty();
    }
}
