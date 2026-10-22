package com.oficina.execucao.adaptador.rest.dto;

import com.oficina.execucao.dominio.Execucao;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ExecucaoResposta(UUID id, UUID osId, long numeroOs, String filialId, String tipo, String status,
                               String mecanico, List<TarefaResposta> tarefas, Instant enfileiradoEm,
                               Instant iniciadoEm, Instant concluidoEm, String motivoRecusa) {
    public static ExecucaoResposta de(Execucao e) {
        return new ExecucaoResposta(e.id(), e.osId(), e.numeroOs(), e.filialId(), e.tipo().name(), e.status().name(),
                e.mecanico(),
                e.tarefas().stream().map(t -> new TarefaResposta(t.descricao(), t.quantidade(), t.registradaEm())).toList(),
                e.enfileiradoEm(), e.iniciadoEm(), e.concluidoEm(),
                e.motivoRecusa() == null ? null : e.motivoRecusa().name());
    }
}
