package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.aplicacao.ProcessadorComandos;
import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/** Retry e DLQ no SQS FIFO emulado: mensagem venenosa é reentregue e vai à DLQ; outro grupo segue. */
class DlqRetryLocalStackTest extends LocalStackSupport {
    static final int MAX_RECEBIMENTOS = 2;
    final SqsClient sqs = sqs();
    final ProcessadorComandos processador = mock(ProcessadorComandos.class);

    String criar(String nome, Map<QueueAttributeName, String> extras) {
        var atributos = new java.util.HashMap<QueueAttributeName, String>(extras);
        atributos.put(QueueAttributeName.FIFO_QUEUE, "true");
        return sqs.createQueue(CreateQueueRequest.builder().queueName(nome).attributes(atributos).build()).queueUrl();
    }

    String arn(String url) {
        return sqs.getQueueAttributes(GetQueueAttributesRequest.builder().queueUrl(url)
                .attributeNames(QueueAttributeName.QUEUE_ARN).build()).attributes().get(QueueAttributeName.QUEUE_ARN);
    }

    void enviar(String fila, String corpo, String grupo) {
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(fila).messageBody(corpo).messageGroupId(grupo)
                .messageDeduplicationId(UUID.randomUUID().toString()).build());
    }

    List<Message> receber(String fila) {
        return sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(fila).maxNumberOfMessages(10)
                .waitTimeSeconds(1).build()).messages();
    }

    @Test void mensagemVenenosaVaiParaDlqAposTentativasEOutroGrupoNaoEBloqueado() throws Exception {
        String sufixo = UUID.randomUUID().toString().substring(0, 8);
        String dlq = criar("dlq-" + sufixo + ".fifo", Map.of());
        String comandos = criar("cmd-" + sufixo + ".fifo", Map.of(
                QueueAttributeName.VISIBILITY_TIMEOUT, "1",
                QueueAttributeName.REDRIVE_POLICY, "{\"deadLetterTargetArn\":\"" + arn(dlq)
                        + "\",\"maxReceiveCount\":" + MAX_RECEBIMENTOS + "}"));
        doThrow(new IllegalStateException("veneno")).when(processador).processar("veneno");
        var listener = new SqsComandosListener(sqs, comandos, processador);

        enviar(comandos, "veneno", "os-1");
        enviar(comandos, "saudavel", "os-2");

        long limite = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        List<Message> naDlq = List.of();
        while (naDlq.isEmpty() && System.nanoTime() < limite) {
            listener.receberUmLote();
            naDlq = receber(dlq);
        }

        assertThat(naDlq).hasSize(1);
        assertThat(naDlq.get(0).body()).isEqualTo("veneno");
        verify(processador, atLeast(MAX_RECEBIMENTOS)).processar("veneno");
        verify(processador, times(1)).processar("saudavel");
        assertThat(receber(comandos)).isEmpty();
    }
}
