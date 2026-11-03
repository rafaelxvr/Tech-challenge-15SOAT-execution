package com.oficina.execucao.adaptador.rest;

import com.oficina.execucao.adaptador.rest.dto.ExecucaoResposta;
import com.oficina.execucao.adaptador.rest.dto.NovaTarefaRequest;
import com.oficina.execucao.aplicacao.AcoesMecanico;
import com.oficina.execucao.aplicacao.ConsultasExecucao;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@Tag(name = "Execução", description = "Fila de diagnóstico e reparo por filial")
@PreAuthorize("hasAnyRole('ADMIN','MECANICO')")
public class ExecucaoController {
    private final AcoesMecanico acoes;
    private final ConsultasExecucao consultas;

    public ExecucaoController(AcoesMecanico acoes, ConsultasExecucao consultas) {
        this.acoes = acoes;
        this.consultas = consultas;
    }

    @GetMapping("/fila")
    @Operation(summary = "Execuções ativas da filial: em andamento primeiro, depois a fila por ordem de chegada")
    public List<ExecucaoResposta> fila(@RequestParam String filialId) {
        return consultas.fila(filialId).stream().map(ExecucaoResposta::de).toList();
    }

    @GetMapping("/execucoes")
    @Operation(summary = "Todas as execuções de uma ordem de serviço")
    public List<ExecucaoResposta> porOs(@RequestParam UUID osId) {
        return consultas.porOs(osId).stream().map(ExecucaoResposta::de).toList();
    }

    @PostMapping("/{id}/iniciar")
    @Operation(summary = "Mecânico assume a execução (NA_FILA → EM_ANDAMENTO)")
    public ExecucaoResposta iniciar(@PathVariable UUID id, Authentication autenticacao) {
        return ExecucaoResposta.de(acoes.iniciar(id, autenticacao.getName()));
    }

    @PostMapping("/{id}/tarefas")
    @Operation(summary = "Registra uma tarefa realizada")
    public ExecucaoResposta registrarTarefa(@PathVariable UUID id, @Valid @RequestBody NovaTarefaRequest pedido) {
        return ExecucaoResposta.de(acoes.registrarTarefa(id, pedido.descricao()));
    }

    @PostMapping("/{id}/concluir")
    @Operation(summary = "Conclui a execução e responde à saga da OS")
    public ExecucaoResposta concluir(@PathVariable UUID id) {
        return ExecucaoResposta.de(acoes.concluir(id));
    }
}
