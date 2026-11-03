package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.mensagem.MensagemInvalidaException;
import com.oficina.execucao.mensagem.TiposMensagem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

public class ProcessadorComandos {
    private static final Logger LOG = LoggerFactory.getLogger(ProcessadorComandos.class);
    private final MapeadorMensagens mapeador;
    private final IniciarDiagnostico iniciarDiagnostico;
    private final IniciarReparo iniciarReparo;
    private final CancelarExecucao cancelarExecucao;

    public ProcessadorComandos(MapeadorMensagens mapeador, IniciarDiagnostico iniciarDiagnostico,
                               IniciarReparo iniciarReparo, CancelarExecucao cancelarExecucao) {
        this.mapeador = mapeador;
        this.iniciarDiagnostico = iniciarDiagnostico;
        this.iniciarReparo = iniciarReparo;
        this.cancelarExecucao = cancelarExecucao;
    }

    public void processar(String corpo) {
        Envelope comando = mapeador.ler(corpo);
        if (comando.kind() != Envelope.Kind.COMMAND) throw new MensagemInvalidaException("Somente comandos são aceitos");
        try {
            MDC.put("correlation_id", comando.correlationId());
            if (comando.traceparent() != null) MDC.put("traceparent", comando.traceparent());
            MDC.put("saga_id", comando.sagaId().toString());
            MDC.put("os_id", comando.osId().toString());
            ResultadoGravacao resultado = switch (comando.type()) {
                case TiposMensagem.INICIAR_DIAGNOSTICO -> iniciarDiagnostico.executar(comando);
                case TiposMensagem.INICIAR_REPARO -> iniciarReparo.executar(comando);
                case TiposMensagem.CANCELAR_EXECUCAO -> cancelarExecucao.executar(comando);
                default -> throw new MensagemInvalidaException("Comando desconhecido");
            };
            MDC.put("event_name", resultado == ResultadoGravacao.DUPLICADA ? "comando_duplicado" : "comando_processado");
            LOG.info("");
        } finally {
            MDC.remove("event_name");
            MDC.remove("correlation_id");
            MDC.remove("traceparent");
            MDC.remove("saga_id");
            MDC.remove("os_id");
        }
    }
}
