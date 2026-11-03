package com.oficina.execucao;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.mensagem.TiposMensagem;
import com.oficina.execucao.support.Fixtures;
import com.oficina.execucao.support.LocalStackSupport;
import com.oficina.execucao.support.TokensTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.*;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "execucao.mensageria.enabled=true")
@AutoConfigureMockMvc
class FluxoMensageriaLocalStackTest extends LocalStackSupport {
    static final String COMANDOS = criarFila("e2e-execution-commands.fifo");
    static final String RESPOSTAS = criarFila("e2e-os-saga-replies.fifo");

    @DynamicPropertySource static void filas(DynamicPropertyRegistry r) {
        r.add("execucao.mensageria.fila-comandos", () -> COMANDOS);
        r.add("execucao.mensageria.fila-respostas", () -> RESPOSTAS);
    }

    @Autowired MockMvc mvc;
    @Autowired MapeadorMensagens mapeador;
    @Autowired RegistroExecucoes registro;
    final SqsClient sqs = sqs();

    Envelope enviar(String tipo, UUID os, int passo, ObjectNode payload) {
        Envelope comando = Fixtures.comando(tipo, os, passo, payload);
        sqs.sendMessage(SendMessageRequest.builder().queueUrl(COMANDOS).messageBody(mapeador.escrever(comando))
                .messageGroupId(os.toString()).messageDeduplicationId(comando.messageId().toString()).build());
        return comando;
    }

    Envelope aguardarResposta(String tipo, UUID os) {
        AtomicReference<Envelope> achada = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            for (Message m : sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(RESPOSTAS)
                    .maxNumberOfMessages(10).waitTimeSeconds(1).build()).messages()) {
                sqs.deleteMessage(DeleteMessageRequest.builder().queueUrl(RESPOSTAS).receiptHandle(m.receiptHandle()).build());
                Envelope e = mapeador.ler(m.body());
                if (e.type().equals(tipo) && e.osId().equals(os)) achada.set(e);
            }
            return achada.get() != null;
        });
        return achada.get();
    }

    UUID aguardarExecucao(UUID os, String tipo) {
        AtomicReference<UUID> id = new AtomicReference<>();
        await().atMost(Duration.ofSeconds(30)).until(() -> {
            registro.porOs(os).stream().filter(e -> e.tipo().name().equals(tipo)).findFirst().ifPresent(e -> id.set(e.id()));
            return id.get() != null;
        });
        return id.get();
    }

    void mecanico(String acao, UUID id) throws Exception {
        mvc.perform(post("/{id}/" + acao, id).header("Authorization", "Bearer " + TokensTeste.staff("MECANICO")))
                .andExpect(status().isOk());
    }

    @Test void diagnosticoReparoECancelamentoPelaFila() throws Exception {
        UUID os = UUID.randomUUID();
        Envelope diag = enviar(TiposMensagem.INICIAR_DIAGNOSTICO, os, 1, JsonNodeFactory.instance.objectNode()
                .put("numero", 42).put("filialId", "MATRIZ").put("versaoOrcamento", 1));
        UUID diagnostico = aguardarExecucao(os, "DIAGNOSTICO");
        mecanico("iniciar", diagnostico);
        mecanico("concluir", diagnostico);
        Envelope concluido = aguardarResposta(TiposMensagem.DIAGNOSTICO_CONCLUIDO, os);
        assertThat(concluido.causationId()).isEqualTo(diag.messageId());
        assertThat(concluido.correlationId()).isEqualTo(diag.correlationId());

        var payloadReparo = JsonNodeFactory.instance.objectNode().put("numero", 42).put("filialId", "MATRIZ");
        payloadReparo.putArray("servicos").addObject().put("descricao", "Troca de óleo").put("quantidade", 1);
        Envelope reparo = enviar(TiposMensagem.INICIAR_REPARO, os, 6, payloadReparo);
        Envelope iniciado = aguardarResposta(TiposMensagem.REPARO_INICIADO, os);
        assertThat(iniciado.causationId()).isEqualTo(reparo.messageId());
        UUID execucao = UUID.fromString(iniciado.payload().get("execucaoId").asText());
        mecanico("iniciar", execucao);
        mecanico("concluir", execucao);
        Envelope finalizado = aguardarResposta(TiposMensagem.EXECUCAO_FINALIZADA, os);
        assertThat(finalizado.causationId()).isEqualTo(reparo.messageId());
        assertThat(finalizado.passo()).isEqualTo(6);

        Envelope cancelar = enviar(TiposMensagem.CANCELAR_EXECUCAO, os, 6,
                JsonNodeFactory.instance.objectNode().put("motivo", "REPARO_RECUSADO"));
        Envelope cancelada = aguardarResposta(TiposMensagem.EXECUCAO_CANCELADA, os);
        assertThat(cancelada.causationId()).isEqualTo(cancelar.messageId());
        assertThat(cancelada.payload().get("canceladas").asInt()).isZero();
    }
}
