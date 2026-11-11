package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.aplicacao.rastreio.PropagadorTraco;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.mensagem.MensagemInvalidaException;
import com.oficina.execucao.mensagem.TiposMensagem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.regex.Pattern;

public class ProcessadorComandos {
    /** Valor seguro para atributo de trace: ASCII imprimível curto, sem espaços (UUID ou id canônico). */
    private static final Pattern ID_CANONICO = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Logger LOG = LoggerFactory.getLogger(ProcessadorComandos.class);
    private final MapeadorMensagens mapeador;
    private final IniciarDiagnostico iniciarDiagnostico;
    private final IniciarReparo iniciarReparo;
    private final CancelarExecucao cancelarExecucao;
    private final PropagadorTraco propagador;

    public ProcessadorComandos(MapeadorMensagens mapeador, IniciarDiagnostico iniciarDiagnostico,
                               IniciarReparo iniciarReparo, CancelarExecucao cancelarExecucao) {
        this(mapeador, iniciarDiagnostico, iniciarReparo, cancelarExecucao, PropagadorTraco.NENHUM);
    }

    public ProcessadorComandos(MapeadorMensagens mapeador, IniciarDiagnostico iniciarDiagnostico,
                               IniciarReparo iniciarReparo, CancelarExecucao cancelarExecucao,
                               PropagadorTraco propagador) {
        this.mapeador = mapeador;
        this.iniciarDiagnostico = iniciarDiagnostico;
        this.iniciarReparo = iniciarReparo;
        this.cancelarExecucao = cancelarExecucao;
        this.propagador = propagador;
    }

    public void processar(String corpo) {
        Envelope comando = mapeador.ler(corpo);
        if (comando.kind() != Envelope.Kind.COMMAND) throw new MensagemInvalidaException("Somente comandos são aceitos");
        try {
            MDC.put("correlation_id", comando.correlationId());
            if (comando.traceparent() != null) MDC.put("traceparent", comando.traceparent());
            anotarTransacao(comando);
            MDC.put("saga_id", comando.sagaId().toString());
            MDC.put("os_id", comando.osId().toString());
            ResultadoGravacao resultado = switch (comando.type()) {
                case TiposMensagem.INICIAR_DIAGNOSTICO -> iniciarDiagnostico.executar(comando);
                case TiposMensagem.INICIAR_REPARO -> iniciarReparo.executar(comando);
                case TiposMensagem.CANCELAR_EXECUCAO -> cancelarExecucao.executar(comando);
                default -> throw new MensagemInvalidaException("Comando desconhecido");
            };
            boolean duplicado = resultado == ResultadoGravacao.DUPLICADA;
            MDC.put("event_name", duplicado ? "comando_duplicado" : "comando_processado");
            LOG.info(duplicado ? "Comando duplicado ignorado" : "Comando processado");
        } finally {
            MDC.remove("event_name");
            MDC.remove("correlation_id");
            MDC.remove("traceparent");
            MDC.remove("saga_id");
            MDC.remove("os_id");
        }
    }

    /** Anota a transação ativa antes da lógica de negócio; só IDs limitados e nunca falha o processamento. */
    private void anotarTransacao(Envelope comando) {
        anotar("sagaId", comando.sagaId().toString());
        String correlacao = comando.correlationId();
        if (ID_CANONICO.matcher(correlacao).matches()) anotar("correlationId", correlacao);
    }

    private void anotar(String chave, String valor) {
        try {
            propagador.anotar(chave, valor);
        } catch (RuntimeException e) {
            // Observabilidade é best effort: não pode afetar o negócio.
        }
    }
}
