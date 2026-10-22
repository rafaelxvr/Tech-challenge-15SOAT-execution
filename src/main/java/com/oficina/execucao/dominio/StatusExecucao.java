package com.oficina.execucao.dominio;

public enum StatusExecucao {
    NA_FILA, EM_ANDAMENTO, CONCLUIDA, CANCELADA, RECUSADA;

    public boolean ativa() { return this == NA_FILA || this == EM_ANDAMENTO; }
}
