package com.oficina.execucao.aplicacao.rastreio;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CarrierRastreioTest {
    static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test void sanitizarMantemSoNomesConhecidosEValoresSeguros() {
        var limpo = CarrierRastreio.sanitizar(Map.of("traceparent", TRACEPARENT, "tracestate", "a=b", "email", "x@y.z",
                "newrelic", "x".repeat(1025)));
        assertThat(limpo).containsOnlyKeys("traceparent", "tracestate");
    }

    @Test void traceparentMalformadoDescartaTodoOCarrier() {
        assertThat(CarrierRastreio.sanitizar(Map.of("traceparent", "00-xx", "tracestate", "a=b"))).isEmpty();
    }
}
