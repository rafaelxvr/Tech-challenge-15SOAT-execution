package com.oficina.execucao.adaptador.dynamo;

import com.oficina.execucao.support.LocalStackSupport;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndexDescription;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefinicaoTabelasTest extends LocalStackSupport {
    @Test void criaTabelasComIndicesEEIdempotente() {
        var nomes = NomesTabelas.comPrefixo("def-" + UUID.randomUUID().toString().substring(0, 8));
        try (var dynamo = dynamo()) {
            DefinicaoTabelas.criarSeAusente(dynamo, nomes);
            DefinicaoTabelas.criarSeAusente(dynamo, nomes);
            assertThat(dynamo.describeTable(b -> b.tableName(nomes.execucoes())).table().globalSecondaryIndexes())
                    .extracting(GlobalSecondaryIndexDescription::indexName)
                    .containsExactlyInAnyOrder(DefinicaoTabelas.INDICE_FILA, DefinicaoTabelas.INDICE_OS);
            assertThat(dynamo.describeTable(b -> b.tableName(nomes.outbox())).table().globalSecondaryIndexes())
                    .extracting(GlobalSecondaryIndexDescription::indexName)
                    .containsExactlyInAnyOrder(DefinicaoTabelas.INDICE_PENDENTES, DefinicaoTabelas.INDICE_OS_SEQUENCIA);
            assertThat(dynamo.describeTable(b -> b.tableName(nomes.inbox())).table().tableName()).isEqualTo(nomes.inbox());
        }
    }
}
