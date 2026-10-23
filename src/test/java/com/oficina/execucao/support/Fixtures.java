package com.oficina.execucao.support;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.execucao.dominio.OrigemComando;
import com.oficina.execucao.mensagem.Envelope;

import java.time.Instant;
import java.util.UUID;

public final class Fixtures {
    public static final String TRACE = "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01";

    private Fixtures() {}

    public static OrigemComando origem() {
        return new OrigemComando(UUID.randomUUID(), UUID.randomUUID(), 6, UUID.randomUUID().toString(), TRACE);
    }

    public static Envelope comando(String tipo, UUID osId, int passo, ObjectNode payload) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, tipo, 1, UUID.randomUUID(), osId, passo, null,
                Instant.parse("2026-09-15T12:00:00Z"), UUID.randomUUID().toString(), TRACE, payload);
    }
}
