package com.oficina.execucao.seguranca;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.ProtectedHeader;
import org.springframework.security.authentication.BadCredentialsException;

import java.security.Key;
import java.time.Clock;
import java.util.Date;
import java.util.Map;
import java.util.Set;

/** Same verification rules as os-service TokenVerification: configured keys only, no header-driven lookup. */
public final class TokenVerification {
    private TokenVerification() {}

    public static Claims verify(String token, String algorithm, Map<String, ? extends Key> keys,
                                String issuer, String audience, String principalType, String purpose, Clock clock) {
        try {
            Claims claims = Jwts.parser().clock(() -> Date.from(clock.instant()))
                    .keyLocator(new LocatorAdapter<Key>() {
                        @Override public Key locate(ProtectedHeader header) {
                            if (!algorithm.equals(header.getAlgorithm()) || header.getKeyId() == null
                                    || Set.of("jku", "x5u", "jwk", "x5c").stream().anyMatch(header::containsKey)) {
                                throw invalid();
                            }
                            Key key = keys.get(header.getKeyId());
                            if (key == null) throw invalid();
                            return key;
                        }
                    })
                    .requireIssuer(issuer).requireAudience(audience)
                    .require("principal_type", principalType).require("token_use", purpose)
                    .build().parseSignedClaims(token).getPayload();
            if (claims.getExpiration() == null || claims.getIssuedAt() == null
                    || !claims.getExpiration().after(Date.from(clock.instant()))
                    || claims.getIssuedAt().after(Date.from(clock.instant()))
                    || !claims.getExpiration().after(claims.getIssuedAt())
                    || !Set.of(audience).equals(claims.getAudience())
                    || claims.getSubject() == null || claims.getSubject().isBlank()) throw invalid();
            return claims;
        } catch (RuntimeException exception) {
            // Parser errors can contain claims or input; never propagate their message/cause to logs or HTTP.
            throw invalid();
        }
    }

    public static BadCredentialsException invalid() {
        return new BadCredentialsException("Invalid credentials");
    }
}
