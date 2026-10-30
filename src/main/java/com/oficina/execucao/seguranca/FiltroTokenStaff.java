package com.oficina.execucao.seguranca;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Accepts only staff access tokens issued by os-service; customer tokens never reach this service. */
public class FiltroTokenStaff extends OncePerRequestFilter {
    private static final Set<String> PAPEIS = Set.of("ADMIN", "MECANICO");
    private final PropriedadesJwt jwt;
    private final Map<String, SecretKey> chaves;
    private final Clock clock;

    public FiltroTokenStaff(PropriedadesJwt jwt, Clock clock) {
        jwt.validar();
        this.jwt = jwt;
        this.chaves = Map.of(jwt.staff().keyId(), Keys.hmacShaKeyFor(jwt.secret().getBytes(StandardCharsets.UTF_8)));
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest pedido, HttpServletResponse resposta, FilterChain cadeia)
            throws ServletException, IOException {
        String cabecalho = pedido.getHeader("Authorization");
        if (cabecalho == null || !cabecalho.startsWith("Bearer ")) {
            cadeia.doFilter(pedido, resposta);
            return;
        }
        try {
            SecurityContextHolder.getContext().setAuthentication(autenticar(cabecalho.substring(7)));
        } catch (BadCredentialsException e) {
            SecurityContextHolder.clearContext();
            resposta.sendError(HttpServletResponse.SC_UNAUTHORIZED);
            return;
        }
        cadeia.doFilter(pedido, resposta);
    }

    Authentication autenticar(String token) {
        Claims claims = TokenVerification.verify(token, "HS256", chaves, jwt.staff().issuer(), jwt.staff().audience(),
                "staff", "access", clock);
        if (claims.containsKey("scopes") || claims.containsKey("identity_version")) throw TokenVerification.invalid();
        if (!(claims.get("roles") instanceof List<?> papeis) || papeis.isEmpty()) throw TokenVerification.invalid();
        List<SimpleGrantedAuthority> autoridades = new ArrayList<>();
        for (Object papel : papeis) {
            if (!(papel instanceof String nome) || !PAPEIS.contains(nome)) throw TokenVerification.invalid();
            autoridades.add(new SimpleGrantedAuthority("ROLE_" + nome));
        }
        return UsernamePasswordAuthenticationToken.authenticated(claims.getSubject(), null, autoridades);
    }
}
