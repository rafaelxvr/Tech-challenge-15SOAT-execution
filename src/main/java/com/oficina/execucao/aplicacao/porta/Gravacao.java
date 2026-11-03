package com.oficina.execucao.aplicacao.porta;

import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.mensagem.Envelope;

import java.util.List;
import java.util.UUID;

/** One atomic unit of work: inbox marker, capacity change, job changes and at most one reply. */
public record Gravacao(MarcaInbox inbox, AjusteCapacidade capacidade, List<Execucao> execucoes, Envelope resposta) {
    public record MarcaInbox(UUID messageId, String tipo) {}

    /** delta +1 reserves a repair slot (bounded by limite); negative deltas release slots unconditionally. */
    public record AjusteCapacidade(String filialId, int delta, int limite) {}

    public Gravacao {
        execucoes = List.copyOf(execucoes);
    }
}
