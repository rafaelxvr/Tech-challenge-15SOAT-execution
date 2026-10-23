package com.oficina.execucao.aplicacao;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.CapacidadeOficina;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.TiposMensagem;
import com.oficina.execucao.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CancelarExecucaoConflitoTest {
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T12:00:00Z"), ZoneOffset.UTC);

    /** Rejects the first {@code conflitos} writes with a concurrency conflict, then accepts. */
    static class RegistroComConflitos implements RegistroExecucoes {
        private int conflitosRestantes;
        final List<Gravacao> gravacoes = new ArrayList<>();
        int leituras;

        RegistroComConflitos(int conflitos) { this.conflitosRestantes = conflitos; }

        @Override public Optional<Execucao> buscar(UUID id) { return Optional.empty(); }
        @Override public List<Execucao> porOs(UUID osId) { leituras++; return List.of(); }
        @Override public List<Execucao> fila(String filialId) { return List.of(); }
        @Override public ResultadoGravacao gravar(Gravacao gravacao) {
            if (conflitosRestantes-- > 0) throw new ConflitoConcorrenciaException();
            gravacoes.add(gravacao);
            return ResultadoGravacao.GRAVADO;
        }
    }

    Envelope cancelar(RegistroComConflitos registro) {
        Envelope comando = Fixtures.comando(TiposMensagem.CANCELAR_EXECUCAO, UUID.randomUUID(), 6,
                JsonNodeFactory.instance.objectNode().put("motivo", "REPARO_RECUSADO"));
        new CancelarExecucao(registro, new FabricaRespostas(CLOCK), new CapacidadeOficina(1), CLOCK).executar(comando);
        return comando;
    }

    @Test void tresConflitosRespondemCompensacaoFalhou() {
        var registro = new RegistroComConflitos(CancelarExecucao.TENTATIVAS);
        Envelope comando = cancelar(registro);
        assertThat(registro.leituras).isEqualTo(CancelarExecucao.TENTATIVAS);
        assertThat(registro.gravacoes).singleElement().satisfies(g -> {
            assertThat(g.execucoes()).isEmpty();
            assertThat(g.capacidade()).isNull();
            assertThat(g.resposta().type()).isEqualTo(TiposMensagem.COMPENSACAO_FALHOU);
            assertThat(g.resposta().causationId()).isEqualTo(comando.messageId());
        });
    }

    @Test void conflitoSeguidoDeSucessoRespondeExecucaoCancelada() {
        var registro = new RegistroComConflitos(CancelarExecucao.TENTATIVAS - 1);
        cancelar(registro);
        assertThat(registro.leituras).isEqualTo(CancelarExecucao.TENTATIVAS);
        assertThat(registro.gravacoes).singleElement().satisfies(g ->
                assertThat(g.resposta().type()).isEqualTo(TiposMensagem.EXECUCAO_CANCELADA));
    }
}
