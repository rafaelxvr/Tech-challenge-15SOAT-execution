package com.oficina.execucao.seguranca;

import com.oficina.execucao.support.TokensTeste;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiltroTokenStaffTest {
    final PropriedadesJwt jwt = new PropriedadesJwt(TokensTeste.SEGREDO,
            new PropriedadesJwt.Staff(TokensTeste.EMISSOR, TokensTeste.AUDIENCIA, TokensTeste.KID));
    final FiltroTokenStaff filtro = new FiltroTokenStaff(jwt, Clock.systemUTC());

    @AfterEach void limpar() { SecurityContextHolder.clearContext(); }

    MockHttpServletResponse executar(String token, MockFilterChain cadeia) throws Exception {
        var pedido = new MockHttpServletRequest();
        if (token != null) pedido.addHeader("Authorization", "Bearer " + token);
        var resposta = new MockHttpServletResponse();
        filtro.doFilter(pedido, resposta, cadeia);
        return resposta;
    }

    @Test void tokenStaffValidoAutenticaComPapeis() throws Exception {
        var cadeia = new MockFilterChain();
        var resposta = executar(TokensTeste.staff("MECANICO"), cadeia);
        assertThat(resposta.getStatus()).isEqualTo(200);
        assertThat(cadeia.getRequest()).isNotNull();
    }

    @Test void semCabecalhoSegueAnonimo() throws Exception {
        var cadeia = new MockFilterChain();
        executar(null, cadeia);
        assertThat(cadeia.getRequest()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test void papelDoTokenViraAutoridade() {
        var autenticacao = filtro.autenticar(TokensTeste.staff("ADMIN"));
        assertThat(autenticacao.getName()).isEqualTo(TokensTeste.MECANICO);
        assertThat(autenticacao.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
    }

    @Test void tokensInvalidosRetornam401SemSeguirCadeia() throws Exception {
        var expirado = TokensTeste.token(TokensTeste.KID, Map.of("principal_type", "staff", "token_use", "access",
                "roles", List.of("ADMIN")), Instant.now().minusSeconds(1));
        var kidErrado = TokensTeste.token("outro", Map.of("principal_type", "staff", "token_use", "access",
                "roles", List.of("ADMIN")), Instant.now().plusSeconds(60));
        var cliente = TokensTeste.token(TokensTeste.KID, Map.of("principal_type", "staff", "token_use", "access",
                "roles", List.of("ADMIN"), "scopes", List.of("orders:read:self")), Instant.now().plusSeconds(60));
        var refresh = TokensTeste.token(TokensTeste.KID, Map.of("principal_type", "staff", "token_use", "refresh",
                "roles", List.of("ADMIN")), Instant.now().plusSeconds(60));
        var semPapel = TokensTeste.token(TokensTeste.KID, Map.of("principal_type", "staff", "token_use", "access",
                "roles", List.of()), Instant.now().plusSeconds(60));
        var papelDesconhecido = TokensTeste.staff("CLIENTE");
        for (String token : List.of(expirado, kidErrado, cliente, refresh, semPapel, papelDesconhecido, "lixo")) {
            var cadeia = new MockFilterChain();
            assertThat(executar(token, cadeia).getStatus()).as(token).isEqualTo(401);
            assertThat(cadeia.getRequest()).isNull();
        }
    }

    @Test void configuracaoIncompletaFalhaNoStartup() {
        assertThatThrownBy(() -> new FiltroTokenStaff(new PropriedadesJwt(" ", jwt.staff()), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FiltroTokenStaff(new PropriedadesJwt(TokensTeste.SEGREDO,
                new PropriedadesJwt.Staff(TokensTeste.EMISSOR, TokensTeste.AUDIENCIA, null)), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
