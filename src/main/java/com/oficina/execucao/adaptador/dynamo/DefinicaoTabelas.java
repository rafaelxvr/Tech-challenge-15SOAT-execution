package com.oficina.execucao.adaptador.dynamo;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/** Table schema used by tests and local runs; infra/ declares the same keys and indexes. */
public final class DefinicaoTabelas {
    public static final String INDICE_FILA = "fila-index";
    public static final String INDICE_OS = "os-index";
    public static final String INDICE_PENDENTES = "estado-disponivel-index";
    public static final String INDICE_OS_SEQUENCIA = "os-sequencia-index";

    private DefinicaoTabelas() {}

    /** Key of the outbox item that lists, in commit order, the not yet published messageIds of an order. */
    public static String chaveFila(String osId) {
        return "FILA#" + osId;
    }

    public static void criarSeAusente(DynamoDbClient dynamo, NomesTabelas nomes) {
        criar(dynamo, CreateTableRequest.builder().tableName(nomes.execucoes()).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("execucaoId", ScalarAttributeType.S), atributo("filaChave", ScalarAttributeType.S),
                        atributo("osId", ScalarAttributeType.S), atributo("enfileiradoEm", ScalarAttributeType.N))
                .keySchema(chave("execucaoId", KeyType.HASH))
                .globalSecondaryIndexes(indice(INDICE_FILA, "filaChave", "enfileiradoEm"),
                        indice(INDICE_OS, "osId", "enfileiradoEm")).build());
        criar(dynamo, CreateTableRequest.builder().tableName(nomes.outbox()).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("messageId", ScalarAttributeType.S), atributo("estado", ScalarAttributeType.S),
                        atributo("disponivelEm", ScalarAttributeType.N), atributo("osId", ScalarAttributeType.S),
                        atributo("sequencia", ScalarAttributeType.N))
                .keySchema(chave("messageId", KeyType.HASH))
                .globalSecondaryIndexes(indice(INDICE_PENDENTES, "estado", "disponivelEm"),
                        indice(INDICE_OS_SEQUENCIA, "osId", "sequencia")).build());
        criar(dynamo, CreateTableRequest.builder().tableName(nomes.inbox()).billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(atributo("messageId", ScalarAttributeType.S))
                .keySchema(chave("messageId", KeyType.HASH)).build());
    }

    private static void criar(DynamoDbClient dynamo, CreateTableRequest pedido) {
        try {
            dynamo.createTable(pedido);
        } catch (ResourceInUseException jaExiste) {
            // Outra inicialização pode ainda estar criando a tabela: aguarda ACTIVE antes de seguir.
        }
        dynamo.waiter().waitUntilTableExists(b -> b.tableName(pedido.tableName()));
    }

    private static AttributeDefinition atributo(String nome, ScalarAttributeType tipo) {
        return AttributeDefinition.builder().attributeName(nome).attributeType(tipo).build();
    }

    private static KeySchemaElement chave(String nome, KeyType tipo) {
        return KeySchemaElement.builder().attributeName(nome).keyType(tipo).build();
    }

    private static GlobalSecondaryIndex indice(String nome, String particao, String ordenacao) {
        return GlobalSecondaryIndex.builder().indexName(nome)
                .keySchema(chave(particao, KeyType.HASH), chave(ordenacao, KeyType.RANGE))
                .projection(Projection.builder().projectionType(ProjectionType.ALL).build()).build();
    }
}
