package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.aplicacao.porta.EnviadorMensagens;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.UUID;

public class SqsEnviadorMensagens implements EnviadorMensagens {
    private final SqsClient sqs;
    private final String fila;

    public SqsEnviadorMensagens(SqsClient sqs, String fila) {
        validarFila(fila);
        this.sqs = sqs;
        this.fila = fila;
    }

    public static void validarFila(String url) {
        if (url == null || url.isBlank() || !url.endsWith(".fifo")) throw new IllegalArgumentException("FIFO queue URL required");
    }

    @Override
    public String enviar(UUID osId, UUID messageId, String corpo) {
        return sqs.sendMessage(SendMessageRequest.builder().queueUrl(fila).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString()).build()).messageId();
    }
}
