package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.CapacidadeOficina;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.OrigemComando;
import com.oficina.execucao.mensagem.*;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/** Saga compensation: idempotent by osId; always answers ExecucaoCancelada with how many jobs it stopped. */
public class CancelarExecucao {
    static final int TENTATIVAS = 3;
    private final RegistroExecucoes registro;
    private final FabricaRespostas respostas;
    private final CapacidadeOficina capacidade;
    private final Clock clock;

    public CancelarExecucao(RegistroExecucoes registro, FabricaRespostas respostas, CapacidadeOficina capacidade, Clock clock) {
        this.registro = registro;
        this.respostas = respostas;
        this.capacidade = capacidade;
        this.clock = clock;
    }

    public ResultadoGravacao executar(Envelope comando) {
        ComandosExecucao.cancelamento(comando.payload());
        OrigemComando origem = FabricaRespostas.origem(comando);
        var inbox = new Gravacao.MarcaInbox(comando.messageId(), comando.type());
        for (int tentativa = 1; ; tentativa++) {
            try {
                return tentar(comando, origem, inbox);
            } catch (ConflitoConcorrenciaException e) {
                if (tentativa == TENTATIVAS) {
                    return registro.gravar(new Gravacao(inbox, null, List.of(), respostas.responder(origem,
                            comando.osId(), TiposMensagem.COMPENSACAO_FALHOU, PayloadsResposta.compensacaoFalhou())));
                }
            }
        }
    }

    private ResultadoGravacao tentar(Envelope comando, OrigemComando origem, Gravacao.MarcaInbox inbox) {
        Instant agora = clock.instant();
        List<Execucao> ativas = registro.porOs(comando.osId()).stream().filter(e -> e.status().ativa()).toList();
        var vagasPorFilial = ativas.stream().filter(Execucao::ocupaVaga)
                .collect(Collectors.groupingBy(Execucao::filialId, Collectors.counting()));
        ativas.forEach(e -> e.cancelar(agora));
        var liberacao = vagasPorFilial.entrySet().stream().findFirst()
                .map(v -> new Gravacao.AjusteCapacidade(v.getKey(), -v.getValue().intValue(), capacidade.limite()))
                .orElse(null);
        return registro.gravar(new Gravacao(inbox, liberacao, ativas, respostas.responder(origem, comando.osId(),
                TiposMensagem.EXECUCAO_CANCELADA, PayloadsResposta.execucaoCancelada(ativas.size()))));
    }
}
