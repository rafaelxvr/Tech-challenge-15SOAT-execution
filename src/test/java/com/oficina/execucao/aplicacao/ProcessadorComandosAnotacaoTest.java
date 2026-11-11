package com.oficina.execucao.aplicacao;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.execucao.aplicacao.porta.ResultadoGravacao;
import com.oficina.execucao.aplicacao.rastreio.PropagadorTraco;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.mensagem.MensagemInvalidaException;
import com.oficina.execucao.mensagem.TiposMensagem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Anotação de sagaId/correlationId na transação New Relic, sem LocalStack. */
class ProcessadorComandosAnotacaoTest {
    static final MapeadorMensagens MAPEADOR = new MapeadorMensagens();
    IniciarDiagnostico diagnostico;
    PropagadorTraco propagador;
    ProcessadorComandos processador;

    @BeforeEach void montar() {
        diagnostico = mock(IniciarDiagnostico.class);
        propagador = mock(PropagadorTraco.class);
        when(diagnostico.executar(any())).thenReturn(ResultadoGravacao.GRAVADO);
        processador = new ProcessadorComandos(MAPEADOR, diagnostico, null, null, propagador);
    }

    static Envelope comando(String correlationId) {
        return new Envelope(UUID.randomUUID(), Envelope.Kind.COMMAND, TiposMensagem.INICIAR_DIAGNOSTICO, 1,
                UUID.randomUUID(), UUID.randomUUID(), 1, null, Instant.parse("2026-09-15T12:00:00Z"), correlationId,
                null, JsonNodeFactory.instance.objectNode());
    }

    @Test void anotaIdsDoEnvelopeAntesDaLogicaDeNegocio() {
        Envelope c = comando(UUID.randomUUID().toString());
        processador.processar(MAPEADOR.escrever(c));
        InOrder ordem = inOrder(propagador, diagnostico);
        ordem.verify(propagador).anotar("sagaId", c.sagaId().toString());
        ordem.verify(propagador).anotar("correlationId", c.correlationId());
        ordem.verify(diagnostico).executar(any());
        verifyNoMoreInteractions(propagador);
    }

    @Test void envelopeSemSagaIdNaoAnotaNada() {
        String corpo = MAPEADOR.escrever(comando(UUID.randomUUID().toString()))
                .replaceFirst("\"sagaId\":\"[^\"]+\",", "");
        assertThatThrownBy(() -> processador.processar(corpo)).isInstanceOf(MensagemInvalidaException.class);
        verifyNoInteractions(propagador, diagnostico);
    }

    @Test void envelopeSemCorrelationIdNaoAnotaNada() {
        String corpo = MAPEADOR.escrever(comando("x")).replaceFirst("\"correlationId\":\"[^\"]+\",", "");
        assertThatThrownBy(() -> processador.processar(corpo)).isInstanceOf(MensagemInvalidaException.class);
        verifyNoInteractions(propagador, diagnostico);
    }

    @Test void correlationIdForaDoPadraoCanonicoNaoVaiParaOTrace() {
        for (String ruim : new String[] {"joao@example.com", "com espaco", "x".repeat(65), ""}) {
            reset(propagador);
            Envelope c = comando(ruim);
            processador.processar(MAPEADOR.escrever(c));
            verify(propagador).anotar("sagaId", c.sagaId().toString());
            verify(propagador, never()).anotar(eq("correlationId"), anyString());
        }
    }

    @Test void falhaDeObservabilidadeNaoAfetaONegocio() {
        doThrow(new IllegalStateException("agente quebrado")).when(propagador).anotar(anyString(), anyString());
        processador.processar(MAPEADOR.escrever(comando(UUID.randomUUID().toString())));
        verify(diagnostico).executar(any());
    }

    @Test void anotaMesmoQuandoONegocioFalha() {
        when(diagnostico.executar(any())).thenThrow(new IllegalStateException("dynamo fora"));
        Envelope c = comando(UUID.randomUUID().toString());
        assertThatThrownBy(() -> processador.processar(MAPEADOR.escrever(c))).isInstanceOf(IllegalStateException.class);
        verify(propagador).anotar("sagaId", c.sagaId().toString());
        assertThat(c.correlationId()).isNotBlank();
    }

    @Test void semPropagadorUsaNoOp() {
        var padrao = new ProcessadorComandos(MAPEADOR, diagnostico, null, null);
        padrao.processar(MAPEADOR.escrever(comando(UUID.randomUUID().toString())));
        verify(diagnostico).executar(any());
    }
}
