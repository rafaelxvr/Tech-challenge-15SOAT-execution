package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.aplicacao.ProcessadorComandos;
import com.oficina.execucao.aplicacao.rastreio.PropagadorTraco;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsComandosListenerTest {
    final SqsClient sqs = mock(SqsClient.class);
    final ProcessadorComandos processador = mock(ProcessadorComandos.class);
    final PropagadorTraco propagador = mock(PropagadorTraco.class);
    final SqsComandosListener listener = new SqsComandosListener(sqs, "https://sqs.local/000/execution-commands.fifo", processador, propagador);

    static Message mensagem(String corpo, String grupo, String recibo) {
        return Message.builder().body(corpo).receiptHandle(recibo)
                .attributes(Map.of(MessageSystemAttributeName.MESSAGE_GROUP_ID, grupo)).build();
    }

    @Test void pedeOsAtributosDeTraceEAceitaOCarrierAntesDaLogica() {
        String traceparent = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        Message comTrace = mensagem("a", "os-1", "r1").toBuilder().messageAttributes(Map.of(
                "traceparent", MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build())).build();
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class)))
                .thenReturn(ReceiveMessageResponse.builder().messages(comTrace).build());
        assertThat(listener.receberUmLote()).isEqualTo(1);
        var captor = ArgumentCaptor.forClass(ReceiveMessageRequest.class);
        verify(sqs).receiveMessage(captor.capture());
        assertThat(captor.getValue().messageAttributeNames()).containsExactlyInAnyOrder("traceparent", "tracestate", "newrelic");
        InOrder ordem = inOrder(propagador, processador);
        ordem.verify(propagador).aceitar(Map.of("traceparent", traceparent));
        ordem.verify(processador).processar("a");
    }

    @Test void processaEApaga() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(mensagem("a", "os-1", "r1"), mensagem("b", "os-2", "r2")).build());
        assertThat(listener.receberUmLote()).isEqualTo(2);
        verify(sqs, times(2)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test void falhaSeguraMensagensPosterioresDoMesmoGrupo() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder()
                .messages(mensagem("falha", "os-1", "r1"), mensagem("depois", "os-1", "r2"), mensagem("outra", "os-2", "r3")).build());
        doThrow(new IllegalStateException("x")).when(processador).processar("falha");
        assertThat(listener.receberUmLote()).isEqualTo(1);
        verify(processador, never()).processar("depois");
        verify(processador).processar("outra");
        verify(sqs, times(1)).deleteMessage(any(DeleteMessageRequest.class));
    }

    @Test void pedeAtributoDeGrupoELongPollingEControlaCicloDeVida() {
        when(sqs.receiveMessage(any(ReceiveMessageRequest.class))).thenReturn(ReceiveMessageResponse.builder().build());
        listener.receberUmLote();
        verify(sqs).receiveMessage(argThat((ReceiveMessageRequest r) -> r.waitTimeSeconds() == 10
                && r.maxNumberOfMessages() == 10
                && r.messageSystemAttributeNames().contains(MessageSystemAttributeName.MESSAGE_GROUP_ID)));
        listener.start();
        assertThat(listener.isRunning()).isTrue();
        listener.stop();
        assertThat(listener.isRunning()).isFalse();
    }
}
