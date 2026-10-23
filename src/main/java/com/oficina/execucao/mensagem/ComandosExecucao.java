package com.oficina.execucao.mensagem;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Anti-corruption parsing of the three command payloads owned by this service. */
public final class ComandosExecucao {
    public record DadosDiagnostico(long numero, String filialId, int versaoOrcamento) {}
    public record ServicoSolicitado(String descricao, int quantidade) {}
    public record DadosReparo(long numero, String filialId, List<ServicoSolicitado> servicos) {}
    public record DadosCancelamento(String motivo) {}

    private ComandosExecucao() {}

    public static DadosDiagnostico diagnostico(JsonNode p) {
        return new DadosDiagnostico(positivo(p, "numero"), texto(p, "filialId"), (int) positivo(p, "versaoOrcamento"));
    }

    public static DadosReparo reparo(JsonNode p) {
        JsonNode lista = p.get("servicos");
        if (lista == null || !lista.isArray()) throw new MensagemInvalidaException("Campo obrigatório ausente: servicos");
        List<ServicoSolicitado> servicos = new ArrayList<>();
        lista.forEach(s -> servicos.add(new ServicoSolicitado(texto(s, "descricao"), (int) positivo(s, "quantidade"))));
        return new DadosReparo(positivo(p, "numero"), texto(p, "filialId"), List.copyOf(servicos));
    }

    public static DadosCancelamento cancelamento(JsonNode p) {
        return new DadosCancelamento(texto(p, "motivo"));
    }

    private static String texto(JsonNode p, String campo) {
        JsonNode v = p.get(campo);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new MensagemInvalidaException("Campo obrigatório ausente: " + campo);
        return v.asText();
    }

    private static long positivo(JsonNode p, String campo) {
        JsonNode v = p.get(campo);
        if (v == null || !v.isIntegralNumber() || v.asLong() < 1) throw new MensagemInvalidaException("Inteiro positivo obrigatório: " + campo);
        return v.asLong();
    }
}
