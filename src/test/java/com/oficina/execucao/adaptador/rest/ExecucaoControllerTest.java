package com.oficina.execucao.adaptador.rest;

import com.oficina.execucao.aplicacao.porta.Gravacao;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.support.Fixtures;
import com.oficina.execucao.support.LocalStackSupport;
import com.oficina.execucao.support.TokensTeste;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ExecucaoControllerTest extends LocalStackSupport {
    @Autowired MockMvc mvc;
    @Autowired RegistroExecucoes registro;

    Execucao diagnostico(UUID os) {
        var e = Execucao.diagnostico(os, 42, "MATRIZ", 1, Fixtures.origem(), Instant.now());
        registro.gravar(new Gravacao(null, null, List.of(e), null));
        return e;
    }

    static String bearer(String... papeis) { return "Bearer " + TokensTeste.staff(papeis); }

    @Test void openApiAutenticadoDocumentaAsRotas() throws Exception {
        mvc.perform(get("/v3/api-docs").header("Authorization", bearer("MECANICO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.paths['/fila']").exists());
    }

    @Test void fluxoDoMecanico() throws Exception {
        UUID os = UUID.randomUUID();
        var e = diagnostico(os);
        mvc.perform(get("/fila").param("filialId", "MATRIZ").header("Authorization", bearer("MECANICO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[?(@.id=='" + e.id() + "')].status").value("NA_FILA"));
        mvc.perform(post("/{id}/iniciar", e.id()).header("Authorization", bearer("MECANICO")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("EM_ANDAMENTO"))
                .andExpect(jsonPath("$.mecanico").value(TokensTeste.MECANICO));
        mvc.perform(post("/{id}/tarefas", e.id()).header("Authorization", bearer("MECANICO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"descricao\":\"Scanner OBD\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.tarefas[0].descricao").value("Scanner OBD"));
        mvc.perform(post("/{id}/concluir", e.id()).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONCLUIDA"));
        mvc.perform(get("/execucoes").param("osId", os.toString()).header("Authorization", bearer("ADMIN")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].tipo").value("DIAGNOSTICO"));
    }

    @Test void errosMapeadosParaHttp() throws Exception {
        var e = diagnostico(UUID.randomUUID());
        mvc.perform(post("/{id}/concluir", e.id()).header("Authorization", bearer("MECANICO")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.erro").value("REGRA_NEGOCIO"));
        mvc.perform(post("/{id}/iniciar", UUID.randomUUID()).header("Authorization", bearer("MECANICO")))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.erro").value("NAO_ENCONTRADA"));
        mvc.perform(get("/fila").param("filialId", "OUTRA").header("Authorization", bearer("MECANICO")))
                .andExpect(status().isNotFound());
        mvc.perform(post("/{id}/tarefas", e.id()).header("Authorization", bearer("MECANICO"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"descricao\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.erro").value("REQUISICAO_INVALIDA"));
        mvc.perform(get("/execucoes").param("osId", "nao-uuid").header("Authorization", bearer("MECANICO")))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/fila").header("Authorization", bearer("MECANICO"))).andExpect(status().isBadRequest());
    }

    @Test void segurancaDosEndpoints() throws Exception {
        mvc.perform(get("/fila").param("filialId", "MATRIZ")).andExpect(status().isUnauthorized());
        mvc.perform(get("/fila").param("filialId", "MATRIZ").header("Authorization", "Bearer lixo"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
