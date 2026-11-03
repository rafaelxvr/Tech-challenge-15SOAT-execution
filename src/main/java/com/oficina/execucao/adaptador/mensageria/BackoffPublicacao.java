package com.oficina.execucao.adaptador.mensageria;

import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;

/** Same policy as os-service: 5 s base, 300 s cap, bounded jitter, blocked on the 12th attempt. */
public final class BackoffPublicacao {
    public static final int MAX_TENTATIVAS = 12;
    private static final long BASE_MS = 5_000L;
    private static final long TETO_MS = 300_000L;
    private final LongUnaryOperator jitter;

    public BackoffPublicacao(LongUnaryOperator jitter) { this.jitter = jitter; }

    public static BackoffPublicacao aleatorio() {
        return new BackoffPublicacao(bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    public Tentativa proxima(int tentativasAnteriores, Instant agora) {
        int numero = Math.min(MAX_TENTATIVAS, tentativasAnteriores + 1);
        long cap = Math.min(TETO_MS, BASE_MS << (numero - 1));
        long offset = Math.max(0, Math.min(cap - BASE_MS, jitter.applyAsLong(cap - BASE_MS + 1)));
        return new Tentativa(numero, numero == MAX_TENTATIVAS, agora.plusMillis(BASE_MS + offset));
    }

    public record Tentativa(int numero, boolean bloqueada, Instant disponivelEm) {}
}
