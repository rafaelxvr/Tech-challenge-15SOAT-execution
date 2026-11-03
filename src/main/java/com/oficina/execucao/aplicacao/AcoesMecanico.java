package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.dominio.CapacidadeOficina;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.TipoExecucao;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.PayloadsResposta;
import com.oficina.execucao.mensagem.TiposMensagem;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/** Mechanic-driven transitions; concluding a job is what answers the saga. */
public class AcoesMecanico {
    private final RegistroExecucoes registro;
    private final FabricaRespostas respostas;
    private final CapacidadeOficina capacidade;
    private final Clock clock;

    public AcoesMecanico(RegistroExecucoes registro, FabricaRespostas respostas, CapacidadeOficina capacidade, Clock clock) {
        this.registro = registro;
        this.respostas = respostas;
        this.capacidade = capacidade;
        this.clock = clock;
    }

    public Execucao iniciar(UUID id, String mecanico) {
        Execucao execucao = carregar(id);
        execucao.iniciar(mecanico, clock.instant());
        registro.gravar(new Gravacao(null, null, List.of(execucao), null));
        return execucao;
    }

    public Execucao registrarTarefa(UUID id, String descricao) {
        Execucao execucao = carregar(id);
        execucao.registrarTarefa(descricao, clock.instant());
        registro.gravar(new Gravacao(null, null, List.of(execucao), null));
        return execucao;
    }

    public Execucao concluir(UUID id) {
        Execucao execucao = carregar(id);
        execucao.concluir(clock.instant());
        boolean reparo = execucao.tipo() == TipoExecucao.REPARO;
        Envelope resposta = reparo
                ? respostas.responder(execucao.origem(), execucao.osId(), TiposMensagem.EXECUCAO_FINALIZADA,
                        PayloadsResposta.execucaoFinalizada(execucao.id(), execucao.concluidoEm()))
                : respostas.responder(execucao.origem(), execucao.osId(), TiposMensagem.DIAGNOSTICO_CONCLUIDO,
                        PayloadsResposta.diagnosticoConcluido(execucao.id()));
        var liberacao = reparo ? new Gravacao.AjusteCapacidade(execucao.filialId(), -1, capacidade.limite()) : null;
        registro.gravar(new Gravacao(null, liberacao, List.of(execucao), resposta));
        return execucao;
    }

    private Execucao carregar(UUID id) {
        return registro.buscar(id).orElseThrow(() -> new NaoEncontradaException("Execução", id));
    }
}
