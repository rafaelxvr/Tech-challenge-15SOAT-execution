package com.oficina.execucao.adaptador.mensageria;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class BackoffPublicacaoTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00Z");

    @Test void baseTetoEBloqueio() {
        assertThat(new BackoffPublicacao(b -> 0).proxima(0, T).disponivelEm()).isEqualTo(T.plusSeconds(5));
        assertThat(new BackoffPublicacao(b -> b - 1).proxima(10, T).disponivelEm()).isEqualTo(T.plusMillis(300_000));
        var ultima = new BackoffPublicacao(b -> 0).proxima(11, T);
        assertThat(ultima.numero()).isEqualTo(12);
        assertThat(ultima.bloqueada()).isTrue();
        assertThat(BackoffPublicacao.aleatorio().proxima(0, T).numero()).isEqualTo(1);
    }
}
