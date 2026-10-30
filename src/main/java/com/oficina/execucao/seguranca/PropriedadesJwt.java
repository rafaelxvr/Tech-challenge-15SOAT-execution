package com.oficina.execucao.seguranca;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.stream.Stream;

@ConfigurationProperties(prefix = "security.jwt")
public record PropriedadesJwt(String secret, Staff staff) {
    public record Staff(String issuer, String audience, String keyId) {}

    void validar() {
        if (staff == null || Stream.of(secret, staff.issuer(), staff.audience(), staff.keyId())
                .anyMatch(v -> v == null || v.isBlank())) {
            throw new IllegalArgumentException("Staff JWT trust must be configured");
        }
    }
}
