package com.oficina.execucao.adaptador.dynamo;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefinicaoTabelasConcorrenciaTest {
    @Test @SuppressWarnings("unchecked")
    void aguardaTabelaAtivaQuandoOutraInicializacaoJaEstaCriando() {
        var dynamo = mock(DynamoDbClient.class);
        var waiter = mock(DynamoDbWaiter.class);
        when(dynamo.waiter()).thenReturn(waiter);
        when(dynamo.createTable(any(CreateTableRequest.class)))
                .thenThrow(ResourceInUseException.builder().message("CREATING").build());

        DefinicaoTabelas.criarSeAusente(dynamo, NomesTabelas.comPrefixo("conc"));

        verify(waiter, times(3)).waitUntilTableExists(any(Consumer.class));
    }
}
