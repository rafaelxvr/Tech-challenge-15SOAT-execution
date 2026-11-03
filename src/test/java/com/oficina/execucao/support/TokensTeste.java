package com.oficina.execucao.support;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

/** HS256 staff tokens shaped exactly like os-service JwtService output (segredo apenas de teste). */
public final class TokensTeste {
    public static final String SEGREDO = "segredo-de-teste-execution-service-com-mais-de-32-bytes";
    public static final String EMISSOR = "oficina-staff-test";
    public static final String AUDIENCIA = "oficina-api-test";
    public static final String KID = "staff-test";
    public static final String MECANICO = "mecanico@oficina.test";

    private TokensTeste() {}

    public static String staff(String... papeis) {
        return token(KID, Map.of("principal_type", "staff", "token_use", "access", "roles", List.of(papeis)),
                Instant.now().plusSeconds(600));
    }

    public static String token(String kid, Map<String, Object> claims, Instant expiraEm) {
        return Jwts.builder().header().keyId(kid).and()
                .issuer(EMISSOR).audience().add(AUDIENCIA).and().subject(MECANICO)
                .issuedAt(Date.from(Instant.now().minusSeconds(5))).expiration(Date.from(expiraEm))
                .claims(claims)
                .signWith(Keys.hmacShaKeyFor(SEGREDO.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }

    public static void propriedades(DynamicPropertyRegistry r) {
        r.add("security.jwt.secret", () -> SEGREDO);
        r.add("security.jwt.staff.issuer", () -> EMISSOR);
        r.add("security.jwt.staff.audience", () -> AUDIENCIA);
        r.add("security.jwt.staff.key-id", () -> KID);
    }
}
