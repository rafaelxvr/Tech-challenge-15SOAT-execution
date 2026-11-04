package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.aplicacao.porta.EnviadorMensagens;
import software.amazon.awssdk.services.sqs.SqsClient;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

public class SqsEnviadorMensagens implements EnviadorMensagens {
    /** Message attribute carrying the W3C trace context so consumers can join the trace without parsing the body. */
    public static final String ATRIBUTO_TRACEPARENT = "traceparent";
    private static final Pattern TRACEPARENT = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");
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
        var requisicao = SendMessageRequest.builder().queueUrl(fila).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString());
        // Only a validated W3C traceparent is propagated; absent or malformed contexts send no attribute.
        String traceparent = MDC.get("traceparent");
        if (traceparent != null && TRACEPARENT.matcher(traceparent).matches()) {
            requisicao.messageAttributes(Map.of(ATRIBUTO_TRACEPARENT,
                    MessageAttributeValue.builder().dataType("String").stringValue(traceparent).build()));
        }
        return sqs.sendMessage(requisicao.build()).messageId();
    }
}
