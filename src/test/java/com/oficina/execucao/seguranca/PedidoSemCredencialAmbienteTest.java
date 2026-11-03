package com.oficina.execucao.seguranca;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;

class PedidoSemCredencialAmbienteTest {
    private final PedidoSemCredencialAmbiente matcher = new PedidoSemCredencialAmbiente();

    @Test
    void pedidoComBearerDispensaCsrf() {
        var pedido = new MockHttpServletRequest("POST", "/x");
        pedido.addHeader("Authorization", "Bearer abc");
        pedido.addHeader("Cookie", "a=b");
        assertThat(matcher.matches(pedido)).isTrue();
    }

    @Test
    void pedidoSemCookieDispensaCsrf() {
        assertThat(matcher.matches(new MockHttpServletRequest("POST", "/x"))).isTrue();
    }

    @Test
    void pedidoComCookieESemBearerExigeCsrf() {
        var pedido = new MockHttpServletRequest("POST", "/x");
        pedido.addHeader("Cookie", "JSESSIONID=1");
        assertThat(matcher.matches(pedido)).isFalse();
        pedido.addHeader("Authorization", "Basic abc");
        assertThat(matcher.matches(pedido)).isFalse();
    }
}
