package com.oficina.execucao.adaptador.dynamo;

import com.oficina.execucao.dominio.DadosExecucao;
import com.oficina.execucao.dominio.MotivoRecusa;
import com.oficina.execucao.dominio.OrigemComando;
import com.oficina.execucao.dominio.StatusExecucao;
import com.oficina.execucao.dominio.Tarefa;
import com.oficina.execucao.dominio.TipoExecucao;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ItemExecucaoMapper {
    private ItemExecucaoMapper() {}

    static String chaveFila(String filialId, StatusExecucao status) { return filialId + "#" + status.name(); }

    static Map<String, AttributeValue> paraItem(DadosExecucao d, long versao) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("execucaoId", s(d.id().toString()));
        item.put("osId", s(d.osId().toString()));
        item.put("numeroOs", n(d.numeroOs()));
        item.put("filialId", s(d.filialId()));
        item.put("tipo", s(d.tipo().name()));
        item.put("statusExecucao", s(d.status().name()));
        item.put("filaChave", s(chaveFila(d.filialId(), d.status())));
        item.put("versaoOrcamento", n(d.versaoOrcamento()));
        item.put("origemMessageId", s(d.origem().messageId().toString()));
        item.put("sagaId", s(d.origem().sagaId().toString()));
        item.put("passo", n(d.origem().passo()));
        item.put("correlationId", s(d.origem().correlationId()));
        if (d.origem().traceparent() != null) item.put("traceparent", s(d.origem().traceparent()));
        if (d.mecanico() != null) item.put("mecanico", s(d.mecanico()));
        item.put("tarefas", AttributeValue.fromL(d.tarefas().stream().map(t -> AttributeValue.fromM(Map.of(
                "descricao", s(t.descricao()), "quantidade", n(t.quantidade()),
                "registradaEm", s(t.registradaEm().toString())))).toList()));
        item.put("enfileiradoEm", n(d.enfileiradoEm().toEpochMilli()));
        if (d.iniciadoEm() != null) item.put("iniciadoEm", n(d.iniciadoEm().toEpochMilli()));
        if (d.concluidoEm() != null) item.put("concluidoEm", n(d.concluidoEm().toEpochMilli()));
        if (d.motivoRecusa() != null) item.put("motivoRecusa", s(d.motivoRecusa().name()));
        item.put("versao", n(versao));
        return item;
    }

    static DadosExecucao deItem(Map<String, AttributeValue> i) {
        var origem = new OrigemComando(UUID.fromString(i.get("origemMessageId").s()), UUID.fromString(i.get("sagaId").s()),
                Integer.parseInt(i.get("passo").n()), i.get("correlationId").s(), texto(i, "traceparent"));
        List<Tarefa> tarefas = i.get("tarefas").l().stream().map(AttributeValue::m).map(t -> new Tarefa(
                t.get("descricao").s(), Integer.parseInt(t.get("quantidade").n()),
                Instant.parse(t.get("registradaEm").s()))).toList();
        return new DadosExecucao(UUID.fromString(i.get("execucaoId").s()), UUID.fromString(i.get("osId").s()),
                Long.parseLong(i.get("numeroOs").n()), i.get("filialId").s(), TipoExecucao.valueOf(i.get("tipo").s()),
                StatusExecucao.valueOf(i.get("statusExecucao").s()), origem, Integer.parseInt(i.get("versaoOrcamento").n()),
                tarefas, texto(i, "mecanico"), instante(i, "enfileiradoEm"), instante(i, "iniciadoEm"),
                instante(i, "concluidoEm"), i.containsKey("motivoRecusa") ? MotivoRecusa.valueOf(i.get("motivoRecusa").s()) : null,
                Long.parseLong(i.get("versao").n()));
    }

    static AttributeValue s(String valor) { return AttributeValue.fromS(valor); }

    static AttributeValue n(long valor) { return AttributeValue.fromN(Long.toString(valor)); }

    private static String texto(Map<String, AttributeValue> i, String campo) {
        return i.containsKey(campo) ? i.get(campo).s() : null;
    }

    private static Instant instante(Map<String, AttributeValue> i, String campo) {
        return i.containsKey(campo) ? Instant.ofEpochMilli(Long.parseLong(i.get(campo).n())) : null;
    }
}
