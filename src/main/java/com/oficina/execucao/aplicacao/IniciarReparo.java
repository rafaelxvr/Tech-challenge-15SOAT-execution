package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.*;
import com.oficina.execucao.mensagem.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/** Accepts a repair into the branch queue when a slot is free; otherwise records and replies a refusal. */
public class IniciarReparo {
    private final RegistroExecucoes registro;
    private final FabricaRespostas respostas;
    private final Filiais filiais;
    private final CapacidadeOficina capacidade;
    private final Clock clock;

    public IniciarReparo(RegistroExecucoes registro, FabricaRespostas respostas, Filiais filiais,
                         CapacidadeOficina capacidade, Clock clock) {
        this.registro = registro;
        this.respostas = respostas;
        this.filiais = filiais;
        this.capacidade = capacidade;
        this.clock = clock;
    }

    public ResultadoGravacao executar(Envelope comando) {
        var dados = ComandosExecucao.reparo(comando.payload());
        OrigemComando origem = FabricaRespostas.origem(comando);
        var inbox = new Gravacao.MarcaInbox(comando.messageId(), comando.type());
        Instant agora = clock.instant();
        if (!filiais.conhece(dados.filialId())) {
            return recusar(comando, dados, origem, inbox, MotivoRecusa.FILIAL_DESCONHECIDA, agora);
        }
        List<Tarefa> servicos = dados.servicos().stream()
                .map(s -> new Tarefa(s.descricao(), s.quantidade(), agora)).toList();
        var execucao = Execucao.reparo(comando.osId(), dados.numero(), dados.filialId(), servicos, origem, agora);
        var resultado = registro.gravar(new Gravacao(inbox,
                new Gravacao.AjusteCapacidade(dados.filialId(), 1, capacidade.limite()), List.of(execucao),
                respostas.responder(origem, comando.osId(), TiposMensagem.REPARO_INICIADO,
                        PayloadsResposta.reparoIniciado(execucao.id()))));
        if (resultado != ResultadoGravacao.CAPACIDADE_ESGOTADA) return resultado;
        return recusar(comando, dados, origem, inbox, MotivoRecusa.CAPACIDADE_ESGOTADA, agora);
    }

    private ResultadoGravacao recusar(Envelope comando, ComandosExecucao.DadosReparo dados, OrigemComando origem,
                                      Gravacao.MarcaInbox inbox, MotivoRecusa motivo, Instant agora) {
        var recusada = Execucao.reparoRecusado(comando.osId(), dados.numero(), dados.filialId(), origem, motivo, agora);
        return registro.gravar(new Gravacao(inbox, null, List.of(recusada), respostas.responder(origem, comando.osId(),
                TiposMensagem.REPARO_RECUSADO, PayloadsResposta.reparoRecusado(motivo))));
    }
}
