package com.oficina.execucao.adaptador.rest.dto;

import java.time.Instant;

public record TarefaResposta(String descricao, int quantidade, Instant registradaEm) {}
