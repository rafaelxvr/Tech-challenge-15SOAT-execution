package com.oficina.execucao.adaptador.dynamo;

public record NomesTabelas(String execucoes, String outbox, String inbox) {
    public static NomesTabelas comPrefixo(String prefixo) {
        return new NomesTabelas(prefixo + "-execucoes", prefixo + "-execucao-outbox", prefixo + "-execucao-inbox");
    }
}
