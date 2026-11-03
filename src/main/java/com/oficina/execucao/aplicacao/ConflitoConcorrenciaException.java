package com.oficina.execucao.aplicacao;

/** Another writer changed the same items first; the whole unit of work may be retried. */
public class ConflitoConcorrenciaException extends RuntimeException {
    public ConflitoConcorrenciaException() { super("Conflito de concorrência"); }
}
