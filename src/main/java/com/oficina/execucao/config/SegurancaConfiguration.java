package com.oficina.execucao.config;

import com.oficina.execucao.seguranca.FiltroTokenStaff;
import com.oficina.execucao.seguranca.PedidoSemCredencialAmbiente;
import com.oficina.execucao.seguranca.PropriedadesJwt;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(PropriedadesJwt.class)
public class SegurancaConfiguration {
    @Bean FiltroTokenStaff filtroTokenStaff(PropriedadesJwt jwt, Clock clock) { return new FiltroTokenStaff(jwt, clock); }

    /** The filter runs only inside the security chain, never as a second global servlet filter. */
    @Bean FilterRegistrationBean<FiltroTokenStaff> registroFiltroTokenStaff(FiltroTokenStaff filtro) {
        var registro = new FilterRegistrationBean<>(filtro);
        registro.setEnabled(false);
        return registro;
    }

    @Bean SecurityFilterChain cadeiaSeguranca(HttpSecurity http, FiltroTokenStaff filtro) throws Exception {
        return http.csrf(c -> c.ignoringRequestMatchers(new PedidoSemCredencialAmbiente()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a.requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .addFilterBefore(filtro, UsernamePasswordAuthenticationFilter.class)
                .build();
    }
}
