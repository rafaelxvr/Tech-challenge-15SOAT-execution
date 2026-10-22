package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.Filiais;
import com.oficina.execucao.dominio.OrigemComando;
import com.oficina.execucao.mensagem.*;

import java.time.Clock;
import java.util.List;

/** Queues a diagnosis; the reply (DiagnosticoConcluido) is sent later when a mechanic concludes it. */
public class IniciarDiagnostico {
    private final RegistroExecucoes registro;
    private final FabricaRespostas respostas;
    private final Filiais filiais;
    private final Clock clock;

    public IniciarDiagnostico(RegistroExecucoes registro, FabricaRespostas respostas, Filiais filiais, Clock clock) {
        this.registro = registro;
        this.respostas = respostas;
        this.filiais = filiais;
        this.clock = clock;
    }

    public ResultadoGravacao executar(Envelope comando) {
        var dados = ComandosExecucao.diagnostico(comando.payload());
        OrigemComando origem = FabricaRespostas.origem(comando);
        var inbox = new Gravacao.MarcaInbox(comando.messageId(), comando.type());
        if (!filiais.conhece(dados.filialId())) {
            return registro.gravar(new Gravacao(inbox, null, List.of(), respostas.responder(origem, comando.osId(),
                    TiposMensagem.DIAGNOSTICO_RECUSADO, PayloadsResposta.diagnosticoRecusado())));
        }
        var execucao = Execucao.diagnostico(comando.osId(), dados.numero(), dados.filialId(), dados.versaoOrcamento(),
                origem, clock.instant());
        return registro.gravar(new Gravacao(inbox, null, List.of(execucao), null));
    }
}
