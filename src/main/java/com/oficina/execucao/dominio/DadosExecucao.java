package com.oficina.execucao.dominio;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Flat snapshot used by persistence adapters; the aggregate owns all behavior. */
public record DadosExecucao(UUID id, UUID osId, long numeroOs, String filialId, TipoExecucao tipo,
                            StatusExecucao status, OrigemComando origem, int versaoOrcamento, List<Tarefa> tarefas,
                            String mecanico, Instant enfileiradoEm, Instant iniciadoEm, Instant concluidoEm,
                            MotivoRecusa motivoRecusa, long versao) {
    public DadosExecucao {
        tarefas = List.copyOf(tarefas);
    }
}
