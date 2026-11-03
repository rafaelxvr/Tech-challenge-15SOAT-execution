package com.oficina.execucao.dominio;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record Tarefa(String descricao, int quantidade, Instant registradaEm) {
    public Tarefa {
        if (descricao == null || descricao.isBlank() || descricao.length() > 200) {
            throw new RegraNegocioException("Descrição da tarefa deve ter de 1 a 200 caracteres.");
        }
        if (quantidade < 1) throw new RegraNegocioException("Quantidade da tarefa deve ser positiva.");
        registradaEm = Objects.requireNonNull(registradaEm, "registradaEm").truncatedTo(ChronoUnit.MILLIS);
    }
}
