package com.oficina.execucao.config;

import com.oficina.execucao.adaptador.dynamo.DefinicaoTabelas;
import com.oficina.execucao.adaptador.dynamo.DynamoRegistroExecucoes;
import com.oficina.execucao.adaptador.dynamo.NomesTabelas;
import com.oficina.execucao.aplicacao.porta.RegistroExecucoes;
import com.oficina.execucao.dominio.CapacidadeOficina;
import com.oficina.execucao.dominio.Filiais;
import com.oficina.execucao.mensagem.FabricaRespostas;
import com.oficina.execucao.mensagem.MapeadorMensagens;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecucaoProperties.class)
public class ExecucaoConfiguration {
    @Bean @ConditionalOnMissingBean Clock clock() { return Clock.systemUTC(); }

    @Bean(destroyMethod = "close") ClientesAws clientesAws(ExecucaoProperties propriedades,
            @Value("${AWS_ROLE_ARN:}") String roleArn, @Value("${AWS_WEB_IDENTITY_TOKEN_FILE:}") String tokenFile) {
        return ClientesAws.criar(propriedades, roleArn, tokenFile);
    }

    @Bean NomesTabelas nomesTabelas(ExecucaoProperties propriedades) {
        var d = propriedades.dynamodb();
        return new NomesTabelas(d.tabelaExecucoes(), d.tabelaOutbox(), d.tabelaInbox());
    }

    @Bean Filiais filiais(ExecucaoProperties propriedades) { return new Filiais(propriedades.filiais()); }

    @Bean CapacidadeOficina capacidadeOficina(ExecucaoProperties propriedades) {
        return new CapacidadeOficina(propriedades.capacidade().reparosPorFilial());
    }

    @Bean FabricaRespostas fabricaRespostas(Clock clock) { return new FabricaRespostas(clock); }

    /** Local/compose and tests create tables on start; cloud tables come from Terraform. */
    @Bean RegistroExecucoes registroExecucoes(ClientesAws clientes, NomesTabelas nomes, MapeadorMensagens mapeador,
                                              Clock clock, ExecucaoProperties propriedades) {
        if (propriedades.dynamodb().criarTabelas()) DefinicaoTabelas.criarSeAusente(clientes.dynamo(), nomes);
        return new DynamoRegistroExecucoes(clientes.dynamo(), nomes, mapeador, clock);
    }
}
