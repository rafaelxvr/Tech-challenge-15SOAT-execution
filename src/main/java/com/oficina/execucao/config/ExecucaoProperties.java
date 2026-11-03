package com.oficina.execucao.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "execucao")
public record ExecucaoProperties(Modo modo, List<String> filiais, Capacidade capacidade, Mensageria mensageria, Dynamodb dynamodb) {
    /** Where the AWS clients point: local emulator with static test credentials, or the real cloud through IRSA. */
    public enum Modo { LOCALSTACK, IRSA }

    public record Capacidade(int reparosPorFilial) {}

    public record Mensageria(boolean enabled, String region, String endpoint, String filaComandos, String filaRespostas) {}

    public record Dynamodb(String region, String endpoint, String tabelaExecucoes, String tabelaOutbox, String tabelaInbox,
                           boolean criarTabelas) {}
}
