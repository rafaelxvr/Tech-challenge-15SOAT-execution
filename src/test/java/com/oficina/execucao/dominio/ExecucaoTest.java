package com.oficina.execucao.dominio;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecucaoTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00.123456789Z");
    static final OrigemComando ORIGEM = new OrigemComando(UUID.randomUUID(), UUID.randomUUID(), 1,
            UUID.randomUUID().toString(), "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01");
    final UUID os = UUID.randomUUID();

    Execucao reparo() {
        return Execucao.reparo(os, 42, "MATRIZ", List.of(new Tarefa("Troca de óleo", 1, T)), ORIGEM, T);
    }

    @Test void diagnosticoNasceNaFilaSemVaga() {
        var e = Execucao.diagnostico(os, 42, "MATRIZ", 1, ORIGEM, T);
        assertThat(e.status()).isEqualTo(StatusExecucao.NA_FILA);
        assertThat(e.tipo()).isEqualTo(TipoExecucao.DIAGNOSTICO);
        assertThat(e.versaoOrcamento()).isEqualTo(1);
        assertThat(e.ocupaVaga()).isFalse();
        assertThat(e.nova()).isTrue();
        assertThat(e.enfileiradoEm()).isEqualTo(Instant.parse("2026-09-15T12:00:00.123Z"));
        assertThat(e.origem()).isEqualTo(ORIGEM);
    }

    @Test void reparoOcupaVagaAteTerminar() {
        var e = reparo();
        assertThat(e.ocupaVaga()).isTrue();
        e.iniciar("mecanico@oficina.test", T);
        assertThat(e.status()).isEqualTo(StatusExecucao.EM_ANDAMENTO);
        assertThat(e.ocupaVaga()).isTrue();
        e.registrarTarefa("Filtro trocado", T.plusSeconds(60));
        e.concluir(T.plusSeconds(120));
        assertThat(e.status()).isEqualTo(StatusExecucao.CONCLUIDA);
        assertThat(e.ocupaVaga()).isFalse();
        assertThat(e.tarefas()).extracting(Tarefa::descricao).containsExactly("Troca de óleo", "Filtro trocado");
        assertThat(e.concluidoEm()).isEqualTo(Instant.parse("2026-09-15T12:02:00.123Z"));
    }

    @Test void transicoesForaDeOrdemSaoRegraDeNegocio() {
        var e = reparo();
        assertThatThrownBy(() -> e.concluir(T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> e.registrarTarefa("x", T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> e.iniciar(" ", T)).isInstanceOf(RegraNegocioException.class);
        e.iniciar("m", T);
        assertThatThrownBy(() -> e.iniciar("m", T)).isInstanceOf(RegraNegocioException.class);
    }

    @Test void cancelarSoAfetaExecucaoAtiva() {
        var e = reparo();
        assertThat(e.cancelar(T)).isTrue();
        assertThat(e.status()).isEqualTo(StatusExecucao.CANCELADA);
        assertThat(e.cancelar(T)).isFalse();
        var recusada = Execucao.reparoRecusado(os, 42, "MATRIZ", ORIGEM, MotivoRecusa.CAPACIDADE_ESGOTADA, T);
        assertThat(recusada.status()).isEqualTo(StatusExecucao.RECUSADA);
        assertThat(recusada.motivoRecusa()).isEqualTo(MotivoRecusa.CAPACIDADE_ESGOTADA);
        assertThat(recusada.cancelar(T)).isFalse();
        assertThat(recusada.ocupaVaga()).isFalse();
    }

    @Test void dadosERestaurarPreservamTudo() {
        var e = reparo();
        e.iniciar("m", T);
        var copia = Execucao.restaurar(e.dados());
        assertThat(copia.dados()).isEqualTo(e.dados());
        assertThat(Execucao.restaurar(new DadosExecucao(e.id(), os, 42, "MATRIZ", TipoExecucao.REPARO,
                StatusExecucao.EM_ANDAMENTO, ORIGEM, 0, e.tarefas(), "m", e.enfileiradoEm(), e.iniciadoEm(),
                null, null, 3)).nova()).isFalse();
    }

    @Test void entradasInvalidasSaoRecusadas() {
        assertThatThrownBy(() -> Execucao.diagnostico(os, 0, "MATRIZ", 1, ORIGEM, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> Execucao.diagnostico(os, 1, " ", 1, ORIGEM, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> Execucao.diagnostico(os, 1, "MATRIZ", 0, ORIGEM, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> new Tarefa("", 1, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> new Tarefa("x".repeat(201), 1, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> new Tarefa("x", 0, T)).isInstanceOf(RegraNegocioException.class);
        assertThatThrownBy(() -> new OrigemComando(UUID.randomUUID(), UUID.randomUUID(), 8, "c", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void filiaisECapacidade() {
        var filiais = new Filiais(List.of("MATRIZ", " FILIAL_SUL "));
        assertThat(filiais.conhece("MATRIZ")).isTrue();
        assertThat(filiais.conhece("FILIAL_SUL")).isTrue();
        assertThat(filiais.conhece("OUTRA")).isFalse();
        assertThat(filiais.conhece(null)).isFalse();
        assertThat(new CapacidadeOficina(5).limite()).isEqualTo(5);
        assertThatThrownBy(() -> new CapacidadeOficina(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Filiais(List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
