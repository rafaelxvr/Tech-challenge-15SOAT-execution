package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.execucao.dominio.OrigemComando;

import java.time.Clock;
import java.util.UUID;

public class FabricaRespostas {
    private final Clock clock;

    public FabricaRespostas(Clock clock) { this.clock = clock; }

    public static OrigemComando origem(Envelope comando) {
        return new OrigemComando(comando.messageId(), comando.sagaId(), comando.passo(), comando.correlationId(),
                comando.traceparent());
    }

    public Envelope responder(OrigemComando origem, UUID osId, String tipo, ObjectNode payload) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.REPLY, tipo, 1, origem.sagaId(), osId, origem.passo(),
                origem.messageId(), clock.instant(), origem.correlationId(), origem.traceparent(), payload);
    }
}
