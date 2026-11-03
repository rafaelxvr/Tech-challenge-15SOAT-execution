package com.oficina.execucao.mensagem;

public class MensagemInvalidaException extends RuntimeException {
    public MensagemInvalidaException(String mensagem) { super(mensagem); }
}
