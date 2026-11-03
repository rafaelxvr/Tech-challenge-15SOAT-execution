package com.oficina.execucao.seguranca;

import com.oficina.execucao.support.TokensTeste;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** S-2: cada variação de token inválido ou hostil precisa terminar em 401 sem seguir a cadeia. */
class MatrizRejeicaoJwtTest {
    static final String OUTRO_SEGREDO = "outro-segredo-de-teste-com-mais-de-32-bytes-xx";
    final PropriedadesJwt jwt = new PropriedadesJwt(TokensTeste.SEGREDO,
            new PropriedadesJwt.Staff(TokensTeste.EMISSOR, TokensTeste.AUDIENCIA, TokensTeste.KID));
    final FiltroTokenStaff filtro = new FiltroTokenStaff(jwt, Clock.systemUTC());
    /** Segredo com 64+ bytes: a mesma chave do verificador serve para assinar HS256, HS384 e HS512. */
    static final String SEGREDO_LONGO = "segredo-longo-de-teste-execution-service-com-pelo-menos-64-bytes-xyz";
    final FiltroTokenStaff filtroLongo = new FiltroTokenStaff(new PropriedadesJwt(SEGREDO_LONGO,
            new PropriedadesJwt.Staff(TokensTeste.EMISSOR, TokensTeste.AUDIENCIA, TokensTeste.KID)), Clock.systemUTC());

    @AfterEach void limpar() { SecurityContextHolder.clearContext(); }

    static SecretKey chave(String segredo) {
        return Keys.hmacShaKeyFor(segredo.getBytes(StandardCharsets.UTF_8));
    }

    /** Builder com tudo válido; cada caso altera apenas um aspecto. */
    static JwtBuilder base(Map<String, Object> claims) {
        return Jwts.builder().header().keyId(TokensTeste.KID).and()
                .issuer(TokensTeste.EMISSOR).audience().add(TokensTeste.AUDIENCIA).and()
                .subject(TokensTeste.MECANICO)
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(Instant.now().plusSeconds(600)))
                .claims(claims);
    }

    static Map<String, Object> validos() {
        return new LinkedHashMap<>(Map.of("principal_type", "staff", "token_use", "access", "roles", List.of("MECANICO")));
    }

    static Map<String, Object> com(String nome, Object valor) {
        var claims = validos();
        claims.put(nome, valor);
        return claims;
    }

    static String assinado(JwtBuilder builder) {
        return builder.signWith(chave(TokensTeste.SEGREDO), Jwts.SIG.HS256).compact();
    }

    static String b64(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    void exigir401(String nome, String token) throws Exception {
        exigir401(filtro, nome, token);
    }

    void exigir401(FiltroTokenStaff filtro, String nome, String token) throws Exception {
        var pedido = new MockHttpServletRequest();
        pedido.addHeader("Authorization", "Bearer " + token);
        var resposta = new MockHttpServletResponse();
        var cadeia = new MockFilterChain();
        filtro.doFilter(pedido, resposta, cadeia);
        assertThat(resposta.getStatus()).as(nome).isEqualTo(401);
        assertThat(cadeia.getRequest()).as(nome + " não segue a cadeia").isNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication()).as(nome).isNull();
    }

    @Test void controleTokenValidoPassa() throws Exception {
        var pedido = new MockHttpServletRequest();
        pedido.addHeader("Authorization", "Bearer " + assinado(base(validos())));
        var cadeia = new MockFilterChain();
        filtro.doFilter(pedido, new MockHttpServletResponse(), cadeia);
        assertThat(cadeia.getRequest()).isNotNull();
    }

    @Test void algoritmoEAssinatura() throws Exception {
        exigir401("assinatura de outro segredo", base(validos()).signWith(chave(OUTRO_SEGREDO), Jwts.SIG.HS256).compact());
        // mesma chave efetiva do verificador; varia só o algoritmo
        var chaveLonga = chave(SEGREDO_LONGO);
        var controle = new MockFilterChain();
        var pedidoControle = new MockHttpServletRequest();
        pedidoControle.addHeader("Authorization", "Bearer " + base(validos()).signWith(chaveLonga, Jwts.SIG.HS256).compact());
        filtroLongo.doFilter(pedidoControle, new MockHttpServletResponse(), controle);
        assertThat(controle.getRequest()).as("controle HS256 com a chave longa passa").isNotNull();
        SecurityContextHolder.clearContext();
        exigir401(filtroLongo, "HS384 com a mesma chave", base(validos()).signWith(chaveLonga, Jwts.SIG.HS384).compact());
        exigir401(filtroLongo, "HS512 com a mesma chave", base(validos()).signWith(chaveLonga, Jwts.SIG.HS512).compact());
        String payload = b64("{\"iss\":\"" + TokensTeste.EMISSOR + "\",\"aud\":\"" + TokensTeste.AUDIENCIA
                + "\",\"sub\":\"x\",\"principal_type\":\"staff\",\"token_use\":\"access\",\"roles\":[\"ADMIN\"],"
                + "\"iat\":" + Instant.now().minusSeconds(5).getEpochSecond()
                + ",\"exp\":" + Instant.now().plusSeconds(600).getEpochSecond() + "}");
        exigir401("alg none com kid", b64("{\"alg\":\"none\",\"kid\":\"" + TokensTeste.KID + "\"}") + "." + payload + ".");
        exigir401("alg none com typ e kid", b64("{\"alg\":\"none\",\"typ\":\"JWT\",\"kid\":\"" + TokensTeste.KID + "\"}") + "." + payload + ".");
        exigir401("assinatura adulterada", assinado(base(validos())) + "x");
        exigir401("partes vazias", "..");
        exigir401("token vazio", "");
    }

    @Test void cabecalhoDoToken() throws Exception {
        exigir401("sem kid", assinado(Jwts.builder().issuer(TokensTeste.EMISSOR)
                .audience().add(TokensTeste.AUDIENCIA).and().subject(TokensTeste.MECANICO)
                .issuedAt(Date.from(Instant.now().minusSeconds(5)))
                .expiration(Date.from(Instant.now().plusSeconds(600))).claims(validos())));
        exigir401("kid desconhecido", assinado(base(validos()).header().keyId("desconhecido").and()));
        exigir401("jku no cabeçalho", assinado(base(validos()).header().add("jku", "http://evil.test/jwks").and()));
        exigir401("x5u no cabeçalho", assinado(base(validos()).header().add("x5u", "http://evil.test/cert").and()));
    }

    @Test void claimsDeIdentidade() throws Exception {
        exigir401("emissor errado", assinado(base(validos()).issuer("outro")));
        exigir401("audiência errada", assinado(base(validos()).audience().single("outra")));
        exigir401("audiência extra", assinado(base(validos()).audience().add("outra").and()));
        exigir401("sem subject", assinado(base(validos()).subject(null)));
        exigir401("subject em branco", assinado(base(validos()).subject(" ")));
        exigir401("principal_type cliente", assinado(base(com("principal_type", "customer"))));
        exigir401("token_use refresh", assinado(base(com("token_use", "refresh"))));
        exigir401("scopes presente", assinado(base(com("scopes", List.of("orders:read:self")))));
        exigir401("identity_version presente", assinado(base(com("identity_version", 1))));
    }

    @Test void papeis() throws Exception {
        exigir401("roles ausente", assinado(base(Map.of("principal_type", "staff", "token_use", "access"))));
        exigir401("roles string", assinado(base(com("roles", "ADMIN"))));
        exigir401("roles vazio", assinado(base(com("roles", List.of()))));
        exigir401("papel minúsculo", assinado(base(com("roles", List.of("admin")))));
        exigir401("papel inválido entre válidos", assinado(base(com("roles", List.of("ADMIN", "ROOT")))));
        exigir401("papel não string", assinado(base(com("roles", List.of(1)))));
    }

    @Test void tempo() throws Exception {
        exigir401("expirado", assinado(base(validos()).expiration(Date.from(Instant.now().minusSeconds(1)))));
        exigir401("sem exp", assinado(Jwts.builder().header().keyId(TokensTeste.KID).and().issuer(TokensTeste.EMISSOR)
                .audience().add(TokensTeste.AUDIENCIA).and().subject(TokensTeste.MECANICO)
                .issuedAt(Date.from(Instant.now().minusSeconds(5))).claims(validos())));
        exigir401("sem iat", assinado(Jwts.builder().header().keyId(TokensTeste.KID).and().issuer(TokensTeste.EMISSOR)
                .audience().add(TokensTeste.AUDIENCIA).and().subject(TokensTeste.MECANICO)
                .expiration(Date.from(Instant.now().plusSeconds(600))).claims(validos())));
        exigir401("iat no futuro", assinado(base(validos()).issuedAt(Date.from(Instant.now().plusSeconds(300)))
                .expiration(Date.from(Instant.now().plusSeconds(900)))));
        exigir401("nbf no futuro", assinado(base(validos()).notBefore(Date.from(Instant.now().plusSeconds(300)))));
    }

    @Test void esquemaNaoReconhecidoNaoAutentica() throws Exception {
        String valido = assinado(base(validos()));
        for (String cabecalho : List.of("Basic " + valido, "bearer " + valido, valido)) {
            var pedido = new MockHttpServletRequest();
            pedido.addHeader("Authorization", cabecalho);
            filtro.doFilter(pedido, new MockHttpServletResponse(), new MockFilterChain());
            assertThat(SecurityContextHolder.getContext().getAuthentication()).as(cabecalho).isNull();
        }
    }
}
