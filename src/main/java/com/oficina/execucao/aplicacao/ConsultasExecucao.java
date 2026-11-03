package com.oficina.execucao.aplicacao;

import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.dominio.Execucao;
import com.oficina.execucao.dominio.Filiais;

import java.util.List;
import java.util.UUID;

public class ConsultasExecucao {
    private final RegistroExecucoes registro;
    private final Filiais filiais;

    public ConsultasExecucao(RegistroExecucoes registro, Filiais filiais) {
        this.registro = registro;
        this.filiais = filiais;
    }

    public List<Execucao> fila(String filialId) {
        if (!filiais.conhece(filialId)) throw new NaoEncontradaException("Filial", filialId);
        return registro.fila(filialId);
    }

    public List<Execucao> porOs(UUID osId) { return registro.porOs(osId); }
}
