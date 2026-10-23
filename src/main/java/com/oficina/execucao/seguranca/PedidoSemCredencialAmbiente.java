package com.oficina.execucao.seguranca;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Pedidos que não dependem de credencial ambiente do navegador: ou autenticam com Bearer explícito,
 * ou não enviam cookie algum. Só esses dispensam o token CSRF; um pedido com cookie e sem Bearer
 * (o vetor clássico de CSRF) continua sujeito à verificação.
 */
public final class PedidoSemCredencialAmbiente implements RequestMatcher {
    @Override
    public boolean matches(HttpServletRequest pedido) {
        String autorizacao = pedido.getHeader("Authorization");
        boolean bearer = autorizacao != null && autorizacao.startsWith("Bearer ");
        return bearer || pedido.getHeader("Cookie") == null;
    }
}
