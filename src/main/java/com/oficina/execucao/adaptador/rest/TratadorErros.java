package com.oficina.execucao.adaptador.rest;

import com.oficina.execucao.adaptador.rest.dto.ErroResposta;
import com.oficina.execucao.aplicacao.ConflitoConcorrenciaException;
import com.oficina.execucao.aplicacao.NaoEncontradaException;
import com.oficina.execucao.dominio.RegraNegocioException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class TratadorErros {
    @ExceptionHandler(RegraNegocioException.class)
    ResponseEntity<ErroResposta> regra(RegraNegocioException e) {
        return resposta(HttpStatus.UNPROCESSABLE_ENTITY, "REGRA_NEGOCIO", e.getMessage());
    }

    @ExceptionHandler(NaoEncontradaException.class)
    ResponseEntity<ErroResposta> naoEncontrada(NaoEncontradaException e) {
        return resposta(HttpStatus.NOT_FOUND, "NAO_ENCONTRADA", e.getMessage());
    }

    @ExceptionHandler(ConflitoConcorrenciaException.class)
    ResponseEntity<ErroResposta> conflito(ConflitoConcorrenciaException e) {
        return resposta(HttpStatus.CONFLICT, "MODIFICACAO_CONCORRENTE", "A execução foi alterada; tente novamente.");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, HttpMessageNotReadableException.class})
    ResponseEntity<ErroResposta> invalida(Exception e) {
        return resposta(HttpStatus.BAD_REQUEST, "REQUISICAO_INVALIDA", "Requisição inválida.");
    }

    private static ResponseEntity<ErroResposta> resposta(HttpStatus status, String codigo, String mensagem) {
        return ResponseEntity.status(status).body(new ErroResposta(codigo, mensagem));
    }
}
