package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MapeadorMensagensTest {
    final MapeadorMensagens mapeador = new MapeadorMensagens();

    @Test void idaEVolta() {
        var e = new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, "IniciarReparo", 1, UUID.randomUUID(),
                UUID.randomUUID(), 6, null, Instant.parse("2026-09-15T12:00:00Z"), UUID.randomUUID().toString(), null,
                JsonNodeFactory.instance.objectNode().put("numero", 1));
        assertThat(mapeador.ler(mapeador.escrever(e))).isEqualTo(e);
    }

    @Test void invalidosSaoRejeitados() {
        assertThatThrownBy(() -> mapeador.ler("{")).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> mapeador.ler("{\"type\":\"X\"}")).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> ComandosExecucao.diagnostico(JsonNodeFactory.instance.objectNode().put("numero", 0)
                .put("filialId", "MATRIZ").put("versaoOrcamento", 1))).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> ComandosExecucao.reparo(JsonNodeFactory.instance.objectNode().put("numero", 1)
                .put("filialId", "MATRIZ"))).isInstanceOf(MensagemInvalidaException.class);
        assertThatThrownBy(() -> ComandosExecucao.cancelamento(JsonNodeFactory.instance.objectNode()))
                .isInstanceOf(MensagemInvalidaException.class);
    }
}
