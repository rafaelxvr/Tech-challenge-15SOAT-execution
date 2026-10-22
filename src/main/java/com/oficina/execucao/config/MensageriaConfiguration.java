package com.oficina.execucao.config;

import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
import com.oficina.execucao.adaptador.mensageria.BackoffPublicacao;
import com.oficina.execucao.adaptador.mensageria.PublicadorOutbox;
import com.oficina.execucao.adaptador.mensageria.SqsComandosListener;
import com.oficina.execucao.adaptador.mensageria.SqsEnviadorMensagens;
import com.oficina.execucao.aplicacao.CancelarExecucao;
import com.oficina.execucao.aplicacao.IniciarDiagnostico;
import com.oficina.execucao.aplicacao.IniciarReparo;
import com.oficina.execucao.aplicacao.ProcessadorComandos;
import com.oficina.execucao.aplicacao.porta.EnviadorMensagens;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.dominio.CapacidadeOficina;
import com.oficina.execucao.dominio.Filiais;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "execucao.mensageria.enabled", havingValue = "true")
public class MensageriaConfiguration {
    @Bean IniciarDiagnostico iniciarDiagnostico(RegistroExecucoes registro, FabricaRespostas respostas, Filiais filiais,
                                                Clock clock) {
        return new IniciarDiagnostico(registro, respostas, filiais, clock);
    }

    @Bean IniciarReparo iniciarReparo(RegistroExecucoes registro, FabricaRespostas respostas, Filiais filiais,
                                      CapacidadeOficina capacidade, Clock clock) {
        return new IniciarReparo(registro, respostas, filiais, capacidade, clock);
    }

    @Bean CancelarExecucao cancelarExecucao(RegistroExecucoes registro, FabricaRespostas respostas,
                                            CapacidadeOficina capacidade, Clock clock) {
        return new CancelarExecucao(registro, respostas, capacidade, clock);
    }

    @Bean ProcessadorComandos processadorComandos(MapeadorMensagens mapeador, IniciarDiagnostico diagnostico,
                                                  IniciarReparo reparo, CancelarExecucao cancelamento) {
        return new ProcessadorComandos(mapeador, diagnostico, reparo, cancelamento);
    }

    @Bean EnviadorMensagens enviadorMensagens(ClientesAws clientes, ExecucaoProperties propriedades) {
        return new SqsEnviadorMensagens(clientes.sqsEnvio(), propriedades.mensageria().filaRespostas());
    }

    @Bean PublicadorOutbox publicadorOutbox(ClientesAws clientes, NomesTabelas nomes, EnviadorMensagens enviador, Clock clock) {
        return new PublicadorOutbox(clientes.dynamo(), nomes, enviador, clock, BackoffPublicacao.aleatorio());
    }

    @Bean PollerPeriodico pollerOutbox(PublicadorOutbox publicador) {
        return new PollerPeriodico("execucao-outbox", () -> {
            for (int i = 0; i < 20 && publicador.publicarProximo(); i++) { /* drain up to 20 per tick */ }
        }, Duration.ofSeconds(1));
    }

    @Bean SqsComandosListener sqsComandosListener(ClientesAws clientes, ExecucaoProperties propriedades,
                                                  ProcessadorComandos processador) {
        SqsEnviadorMensagens.validarFila(propriedades.mensageria().filaComandos());
        return new SqsComandosListener(clientes.sqsRecepcao(), propriedades.mensageria().filaComandos(), processador);
    }
}
