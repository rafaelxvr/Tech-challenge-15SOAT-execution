package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Wire format shared by all Phase 4 services (Plan 0 §4.1). */
public record Envelope(UUID messageId, Kind kind, String type, int schemaVersion, UUID sagaId, UUID osId,
                       int passo, UUID causationId, Instant occurredAt, String correlationId, String traceparent,
                       JsonNode payload) {
    public static final int MAX_BYTES = 65_536;

    public enum Kind { COMMAND, REPLY, EVENT }

    public Envelope {
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(osId, "osId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(payload, "payload");
        if (schemaVersion < 1 || passo < 1 || passo > 7) throw new IllegalArgumentException("Versão ou passo inválido");
        if (kind == Kind.REPLY && causationId == null) throw new IllegalArgumentException("Resposta exige causationId");
        if (!payload.isObject()) throw new IllegalArgumentException("Payload deve ser objeto");
    }
}
