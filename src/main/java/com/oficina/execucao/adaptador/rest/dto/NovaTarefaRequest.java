package com.oficina.execucao.adaptador.rest.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record NovaTarefaRequest(@NotBlank @Size(max = 200) String descricao) {}
