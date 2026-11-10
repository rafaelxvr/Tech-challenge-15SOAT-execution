package com.oficina.execucao.aplicacao.rastreio;

import java.util.Map;

/** Distributed-trace context crossing the SQS boundary; every operation is best effort and never fails the saga. */
public interface PropagadorTraco {
    /** Sem rastreio: usado onde não há agente (testes e composição manual). */
    PropagadorTraco NENHUM = new PropagadorTraco() {
        @Override public Map<String, String> capturar() { return Map.of(); }
        @Override public void aceitar(Map<String, String> headers) { }
        @Override public void anotar(String chave, String valor) { }
    };

    /** Headers (traceparent, tracestate, newrelic) of the transaction active on this thread; empty when none. */
    Map<String, String> capturar();

    /** Joins the active transaction to the trace carried by a consumed message. */
    void aceitar(Map<String, String> headers);

    /** Adds a custom attribute to the active transaction. */
    void anotar(String chave, String valor);
}
