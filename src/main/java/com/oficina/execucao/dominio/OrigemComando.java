package com.oficina.execucao.dominio;

import java.util.Objects;
import java.util.UUID;

/** The saga command a job answers to; replies triggered later by a mechanic reuse it verbatim. */
public record OrigemComando(UUID messageId, UUID sagaId, int passo, String correlationId, String traceparent) {
    public OrigemComando {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (passo < 1 || passo > 7) throw new IllegalArgumentException("Passo inválido");
    }
}
