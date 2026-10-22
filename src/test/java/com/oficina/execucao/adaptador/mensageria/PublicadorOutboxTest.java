package com.oficina.execucao.adaptador.mensageria;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.oficina.execucao.adaptador.dynamo.DefinicaoTabelas;
import com.oficina.execucao.adaptador.dynamo.DynamoRegistroExecucoes;
import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.mensagem.Envelope;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import com.oficina.execucao.support.Fixtures;
import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;

import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class PublicadorOutboxTest extends LocalStackSupport {
    static final AtomicReference<Instant> AGORA = new AtomicReference<>(Instant.parse("2026-09-15T12:00:00Z"));
    static final Clock CLOCK = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return AGORA.get(); }
    };
    static DynamoDbClient dynamo;
    static SqsClient sqs;
    static NomesTabelas nomes;
    static DynamoRegistroExecucoes registro;
    static String fila;

    @BeforeAll static void montar() {
        dynamo = dynamo();
        sqs = sqs();
        nomes = NomesTabelas.comPrefixo("pub-" + UUID.randomUUID().toString().substring(0, 8));
        DefinicaoTabelas.criarSeAusente(dynamo, nomes);
        registro = new DynamoRegistroExecucoes(dynamo, nomes, new MapeadorMensagens(), CLOCK);
        fila = criarFila("pub-" + UUID.randomUUID().toString().substring(0, 8) + ".fifo");
    }

    @AfterAll static void fechar() { dynamo.close(); sqs.close(); }

    Envelope resposta(UUID osId, String tipo) {
        var e = new FabricaRespostas(CLOCK).responder(Fixtures.origem(), osId, tipo, JsonNodeFactory.instance.objectNode());
        registro.gravar(new Gravacao(null, null, List.of(), e));
        AGORA.set(AGORA.get().plusMillis(1));
        return e;
    }

    Map<String, AttributeValue> item(UUID messageId) {
        return dynamo.getItem(b -> b.tableName(nomes.outbox()).consistentRead(true)
                .key(Map.of("messageId", AttributeValue.fromS(messageId.toString())))).item();
    }

    void drenar(PublicadorOutbox p) { while (p.publicarProximo()) { /* until no work */ } }

    @Test void publicaComGrupoEDedupEMarcaPublicado() {
        UUID os = UUID.randomUUID();
        var r = resposta(os, "ReparoIniciado");
        var publicador = new PublicadorOutbox(dynamo, nomes, new SqsEnviadorMensagens(sqs, fila), CLOCK, new BackoffPublicacao(b -> 0), Duration.ZERO);
        drenar(publicador);
        var recebidas = sqs.receiveMessage(ReceiveMessageRequest.builder().queueUrl(fila).maxNumberOfMessages(10)
                .waitTimeSeconds(2).messageSystemAttributeNames(MessageSystemAttributeName.MESSAGE_GROUP_ID,
                        MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID).build()).messages();
        assertThat(recebidas).anySatisfy(m -> {
            assertThat(m.attributes().get(MessageSystemAttributeName.MESSAGE_GROUP_ID)).isEqualTo(os.toString());
            assertThat(m.attributes().get(MessageSystemAttributeName.MESSAGE_DEDUPLICATION_ID)).isEqualTo(r.messageId().toString());
            assertThat(new MapeadorMensagens().ler(m.body())).isEqualTo(r);
        });
        assertThat(item(r.messageId()).get("estado").s()).isEqualTo("PUBLISHED");
        assertThat(item(r.messageId())).containsKeys("sqsMessageId", "expiraEm");
    }

    @Test void respostaPosteriorDaMesmaOsEsperaAnterior() {
        UUID os = UUID.randomUUID();
        var primeira = resposta(os, "ReparoIniciado");
        var segunda = resposta(os, "ExecucaoFinalizada");
        List<UUID> enviadas = new ArrayList<>();
        var falhando = new PublicadorOutbox(dynamo, nomes, (o, id, corpo) -> { throw new IllegalStateException("x"); },
                CLOCK, new BackoffPublicacao(b -> 0), Duration.ZERO);
        while (item(primeira.messageId()).get("tentativas").n().equals("0")) falhando.publicarProximo();
        var publicador = new PublicadorOutbox(dynamo, nomes, (o, id, corpo) -> { enviadas.add(id); return "ok-" + id; },
                CLOCK, new BackoffPublicacao(b -> 0), Duration.ZERO);
        drenar(publicador);
        assertThat(enviadas).doesNotContain(segunda.messageId());
        assertThat(item(primeira.messageId()).get("ultimoErro").s()).isEqualTo("QUEUE_SEND_FAILED");
        AGORA.set(AGORA.get().plusSeconds(6));
        drenar(publicador);
        assertThat(enviadas).containsSubsequence(primeira.messageId(), segunda.messageId());
    }

    @Test void falhaNaoSobrescrevePublicadoPorOutroPod() {
        var r = resposta(UUID.randomUUID(), "ReparoIniciado");
        var publicador = new PublicadorOutbox(dynamo, nomes, (o, id, corpo) -> {
            dynamo.updateItem(b -> b.tableName(nomes.outbox())
                    .key(Map.of("messageId", AttributeValue.fromS(id.toString())))
                    .updateExpression("SET estado = :p").expressionAttributeValues(Map.of(":p", AttributeValue.fromS("PUBLISHED"))));
            throw new IllegalStateException("x");
        }, CLOCK, new BackoffPublicacao(b -> 0), Duration.ZERO);
        publicador.publicarProximo();
        assertThat(item(r.messageId()).get("estado").s()).isEqualTo("PUBLISHED");
    }

    @Test void naoPublicaAntesDaJanelaDeAssentamento() {
        var r = resposta(UUID.randomUUID(), "ReparoIniciado");
        List<UUID> enviadas = new ArrayList<>();
        var publicador = new PublicadorOutbox(dynamo, nomes, (o, id, corpo) -> { enviadas.add(id); return "ok"; },
                CLOCK, new BackoffPublicacao(b -> 0), Duration.ofSeconds(2));
        assertThat(publicador.publicarProximo()).isFalse();
        AGORA.set(AGORA.get().plusSeconds(3));
        publicador.publicarProximo();
        assertThat(enviadas).contains(r.messageId());
    }

    @Test void pagina_alemDosPrimeirosCandidatosBloqueados() {
        var tabelasLocais = NomesTabelas.comPrefixo("pag-" + UUID.randomUUID().toString().substring(0, 8));
        DefinicaoTabelas.criarSeAusente(dynamo, tabelasLocais);
        var reg = new DynamoRegistroExecucoes(dynamo, tabelasLocais, new MapeadorMensagens(), CLOCK);
        UUID bloqueadora = UUID.randomUUID();
        for (int i = 0; i < 2; i++) {
            reg.gravar(new Gravacao(null, null, List.of(), new FabricaRespostas(CLOCK)
                    .responder(Fixtures.origem(), bloqueadora, "ReparoIniciado", JsonNodeFactory.instance.objectNode())));
            AGORA.set(AGORA.get().plusMillis(1));
        }
        // 30 OS livres depois da fila bloqueada: o primeiro item da OS bloqueadora é BLOCKED
        var primeiro = dynamo.scan(b -> b.tableName(tabelasLocais.outbox())).items().stream()
                .min(Comparator.comparing(m -> Long.parseLong(m.get("sequencia").n()))).orElseThrow();
        dynamo.updateItem(b -> b.tableName(tabelasLocais.outbox()).key(Map.of("messageId", primeiro.get("messageId")))
                .updateExpression("SET estado = :b").expressionAttributeValues(Map.of(":b", AttributeValue.fromS("BLOCKED"))));
        Set<UUID> livres = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            var e = new FabricaRespostas(CLOCK).responder(Fixtures.origem(), UUID.randomUUID(), "ReparoIniciado",
                    JsonNodeFactory.instance.objectNode());
            reg.gravar(new Gravacao(null, null, List.of(), e));
            livres.add(e.messageId());
            AGORA.set(AGORA.get().plusMillis(1));
        }
        List<UUID> enviadas = new ArrayList<>();
        var publicador = new PublicadorOutbox(dynamo, tabelasLocais, (o, id, corpo) -> { enviadas.add(id); return "ok"; },
                CLOCK, new BackoffPublicacao(b -> 0), Duration.ZERO);
        drenar(publicador);
        assertThat(enviadas).containsExactlyInAnyOrderElementsOf(livres);
    }

    @Test void filaInvalidaERecusada() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> SqsEnviadorMensagens.validarFila("https://x/fila"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SqsEnviadorMensagens(sqs, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
