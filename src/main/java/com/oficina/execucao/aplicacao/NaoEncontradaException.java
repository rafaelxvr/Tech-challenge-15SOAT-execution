package com.oficina.execucao.aplicacao;

public class NaoEncontradaException extends RuntimeException {
    public NaoEncontradaException(String recurso, Object id) {
        super("%s '%s' não encontrada.".formatted(recurso, id));
    }
}
