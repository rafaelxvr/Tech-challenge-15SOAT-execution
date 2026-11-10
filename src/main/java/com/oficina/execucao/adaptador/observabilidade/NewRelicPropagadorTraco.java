package com.oficina.execucao.adaptador.observabilidade;

import com.newrelic.api.agent.HeaderType;
import com.newrelic.api.agent.Headers;
import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Transaction;
import com.newrelic.api.agent.TransportType;
import com.oficina.execucao.aplicacao.rastreio.CarrierRastreio;
import com.oficina.execucao.aplicacao.rastreio.PropagadorTraco;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** New Relic implementation; without the agent attached the API is a no-op and the carrier comes back empty. */
@Component
public class NewRelicPropagadorTraco implements PropagadorTraco {
    private final Supplier<Transaction> transacao;
    private final BiConsumer<String, String> atributo;

    public NewRelicPropagadorTraco() {
        this(() -> NewRelic.getAgent().getTransaction(), NewRelic::addCustomParameter);
    }

    NewRelicPropagadorTraco(Supplier<Transaction> transacao, BiConsumer<String, String> atributo) {
        this.transacao = transacao;
        this.atributo = atributo;
    }

    @Override public Map<String, String> capturar() {
        try {
            MapaHeaders headers = new MapaHeaders();
            transacao.get().insertDistributedTraceHeaders(headers);
            return CarrierRastreio.sanitizar(headers.valores);
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    @Override public void aceitar(Map<String, String> headers) {
        Map<String, String> limpo = CarrierRastreio.sanitizar(headers);
        if (limpo.isEmpty()) return;
        try {
            transacao.get().acceptDistributedTraceHeaders(TransportType.Queue, new MapaHeaders(limpo));
        } catch (RuntimeException e) {
            // Tracing must never fail message handling.
        }
    }

    @Override public void anotar(String chave, String valor) {
        try {
            atributo.accept(chave, valor);
        } catch (RuntimeException e) {
            // Tracing must never fail message handling.
        }
    }

    /** Case-insensitive message-header carrier handed to the agent. */
    private static final class MapaHeaders implements Headers {
        private final Map<String, String> valores = new LinkedHashMap<>();

        MapaHeaders() {}

        MapaHeaders(Map<String, String> iniciais) { iniciais.forEach(this::setHeader); }

        @Override public HeaderType getHeaderType() { return HeaderType.MESSAGE; }
        @Override public String getHeader(String nome) { return valores.get(nome.toLowerCase()); }
        @Override public Collection<String> getHeaders(String nome) {
            String valor = getHeader(nome);
            return valor == null ? List.of() : List.of(valor);
        }
        @Override public void setHeader(String nome, String valor) { valores.put(nome.toLowerCase(), valor); }
        @Override public void addHeader(String nome, String valor) { setHeader(nome, valor); }
        @Override public Collection<String> getHeaderNames() { return valores.keySet(); }
        @Override public boolean containsHeader(String nome) { return valores.containsKey(nome.toLowerCase()); }
    }
}
