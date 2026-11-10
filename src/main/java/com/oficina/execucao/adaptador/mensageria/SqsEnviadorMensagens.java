package com.oficina.execucao.adaptador.mensageria;

import com.oficina.execucao.aplicacao.porta.EnviadorMensagens;
import com.oficina.execucao.aplicacao.rastreio.CarrierRastreio;
import software.amazon.awssdk.services.sqs.SqsClient;
import org.slf4j.MDC;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

import java.util.LinkedHashMap;
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
    public String enviar(UUID osId, UUID messageId, String corpo, Map<String, String> traco) {
        var requisicao = SendMessageRequest.builder().queueUrl(fila).messageBody(corpo)
                .messageGroupId(osId.toString()).messageDeduplicationId(messageId.toString());
        // Carrier persistido primeiro (capturado na transação consumidora); o traceparent validado do MDC é o fallback.
        Map<String, String> carrier = new LinkedHashMap<>(CarrierRastreio.sanitizar(traco));
        String traceparent = MDC.get("traceparent");
        if (carrier.isEmpty() && traceparent != null && TRACEPARENT.matcher(traceparent).matches()) {
            carrier.put(ATRIBUTO_TRACEPARENT, traceparent);
        }
        if (!carrier.isEmpty()) {
            Map<String, MessageAttributeValue> atributos = new LinkedHashMap<>();
            carrier.forEach((nome, valor) -> atributos.put(nome,
                    MessageAttributeValue.builder().dataType("String").stringValue(valor).build()));
            requisicao.messageAttributes(atributos);
        }
        return sqs.sendMessage(requisicao.build()).messageId();
    }
}
