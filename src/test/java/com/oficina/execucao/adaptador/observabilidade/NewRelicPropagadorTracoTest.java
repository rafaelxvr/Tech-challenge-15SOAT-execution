package com.oficina.execucao.adaptador.observabilidade;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransportType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NewRelicPropagadorTracoTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
    final Transaction transacao = mock(Transaction.class);
    final List<String> atributos = new ArrayList<>();
    final NewRelicPropagadorTraco propagador = new NewRelicPropagadorTraco(() -> transacao,
            (chave, valor) -> atributos.add(chave + "=" + valor));

    @Test void capturaOsHeadersQueOAgenteInsere() {
        doAnswer(i -> {
            Headers h = i.getArgument(0);
            assertThat(h.getHeaderType()).isEqualTo(HeaderType.MESSAGE);
            h.setHeader("traceparent", TRACEPARENT);
            h.setHeader("tracestate", "123@nr=0-0-1-2-3");
            h.setHeader("newrelic", "eyJ2IjpbMCwxXX0=");
            return null;
        }).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).containsEntry("traceparent", TRACEPARENT)
                .containsEntry("tracestate", "123@nr=0-0-1-2-3").containsEntry("newrelic", "eyJ2IjpbMCwxXX0=");
    }

    @Test void semAgenteOCarrierVemVazio() {
        assertThat(propagador.capturar()).isEmpty();
    }

    @Test void falhaDoAgenteNaoQuebraACaptura() {
        doThrow(new IllegalStateException("agent")).when(transacao).insertDistributedTraceHeaders(any());
        assertThat(propagador.capturar()).isEmpty();
    }

    @Test void aceitaOsHeadersComoFilaSemDistinguirCaixa() {
        propagador.aceitar(Map.of("traceparent", TRACEPARENT, "tracestate", "123@nr=0-0-1-2-3"));
        var captor = ArgumentCaptor.forClass(Headers.class);
        verify(transacao).acceptDistributedTraceHeaders(eq(TransportType.Queue), captor.capture());
        Headers h = captor.getValue();
        assertThat(h.getHeaderType()).isEqualTo(HeaderType.MESSAGE);
        assertThat(h.getHeader("TraceParent")).isEqualTo(TRACEPARENT);
        assertThat(h.containsHeader("tracestate")).isTrue();
        assertThat(h.getHeader("newrelic")).isNull();
    }

    @Test void naoAceitaCarrierVazioOuMalformado() {
        propagador.aceitar(Map.of());
        propagador.aceitar(Map.of("traceparent", "malformado"));
        verifyNoInteractions(transacao);
    }

    @Test void anotaAtributoCustomizado() {
        propagador.anotar("sagaId", "id");
        assertThat(atributos).containsExactly("sagaId=id");
    }
}
