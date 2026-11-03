package com.oficina.execucao.aplicacao.porta;

import com.oficina.execucao.dominio.Execucao;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RegistroExecucoes {
    Optional<Execucao> buscar(UUID id);
    List<Execucao> porOs(UUID osId);
    /** Active jobs of a branch: in progress first, then queued, each oldest first. */
    List<Execucao> fila(String filialId);
    ResultadoGravacao gravar(Gravacao gravacao);
}
