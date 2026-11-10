package com.oficina.execucao.aplicacao.rastreio;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** The only trace headers allowed on a queue message: a closed name list and bounded printable-ASCII values. */
public final class CarrierRastreio {
    public static final String TRACEPARENT = "traceparent";
    public static final String TRACESTATE = "tracestate";
    public static final String NEWRELIC = "newrelic";
    public static final List<String> NOMES = List.of(TRACEPARENT, TRACESTATE, NEWRELIC);
    private static final int MAX_VALOR = 1024;
    private static final Pattern VALOR = Pattern.compile("[\\x20-\\x7E]{1," + MAX_VALOR + "}");
    private static final Pattern TRACEPARENT_VALIDO = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private CarrierRastreio() {}

    /** Keeps only known headers with a safe value; a malformed traceparent drops the whole carrier. */
    public static Map<String, String> sanitizar(Map<String, String> bruto) {
        Map<String, String> limpo = new LinkedHashMap<>();
        if (bruto == null) return limpo;
        for (String nome : NOMES) {
            String valor = bruto.get(nome);
            if (valor != null && VALOR.matcher(valor).matches()) limpo.put(nome, valor);
        }
        String traceparent = limpo.get(TRACEPARENT);
        if (traceparent != null && !TRACEPARENT_VALIDO.matcher(traceparent).matches()) limpo.clear();
        return limpo;
    }
}
