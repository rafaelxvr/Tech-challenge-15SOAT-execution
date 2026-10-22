package com.oficina.execucao.adaptador.dynamo;

import com.oficina.execucao.dominio.DadosExecucao;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.MotivoRecusa;
import com.oficina.execucao.dominio.OrigemComando;
import com.oficina.execucao.dominio.StatusExecucao;
import com.oficina.execucao.dominio.Tarefa;
import com.oficina.execucao.dominio.TipoExecucao;
import com.oficina.execucao.support.Fixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ItemExecucaoMapperTest {
    static final Instant T = Instant.parse("2026-09-15T12:00:00.123Z");

    @Test void idaEVoltaComCamposOpcionais() {
        var e = Execucao.reparo(UUID.randomUUID(), 42, "MATRIZ", List.of(new Tarefa("Óleo", 2, T)), Fixtures.origem(), T);
        e.iniciar("m@x", T.plusSeconds(1));
        var item = ItemExecucaoMapper.paraItem(e.dados(), 4);
        assertThat(item.get("filaChave").s()).isEqualTo("MATRIZ#EM_ANDAMENTO");
        assertThat(item.get("versao").n()).isEqualTo("4");
        var lido = ItemExecucaoMapper.deItem(item);
        assertThat(lido).isEqualTo(new DadosExecucao(e.id(), e.osId(), 42, "MATRIZ", TipoExecucao.REPARO,
                StatusExecucao.EM_ANDAMENTO, e.origem(), 0, e.tarefas(), "m@x", e.enfileiradoEm(), e.iniciadoEm(),
                null, null, 4));
    }

    @Test void recusadaSemTraceparentNemMecanico() {
        var origem = new OrigemComando(UUID.randomUUID(), UUID.randomUUID(), 6, UUID.randomUUID().toString(), null);
        var e = Execucao.reparoRecusado(UUID.randomUUID(), 7, "MATRIZ", origem, MotivoRecusa.CAPACIDADE_ESGOTADA, T);
        var item = ItemExecucaoMapper.paraItem(e.dados(), 1);
        assertThat(item).doesNotContainKeys("traceparent", "mecanico", "iniciadoEm", "concluidoEm");
        assertThat(ItemExecucaoMapper.deItem(item).motivoRecusa()).isEqualTo(MotivoRecusa.CAPACIDADE_ESGOTADA);
    }
}
