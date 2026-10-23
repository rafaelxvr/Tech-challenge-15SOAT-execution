package com.oficina.execucao.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

import java.util.Map;
import java.util.UUID;

import static org.testcontainers.containers.localstack.LocalStackContainer.Service.DYNAMODB;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

/** One LocalStack (DynamoDB + SQS) per JVM. Docker is required; tests never silently skip. */
public abstract class LocalStackSupport {
    public static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.8")).withServices(DYNAMODB, SQS);
    /** Unique per JVM so Spring contexts never reuse tables written by plain integration tests. */
    public static final String PREFIXO_CONTEXTO = "ctx-" + UUID.randomUUID().toString().substring(0, 8);

    static { LOCALSTACK.start(); }

    // Credenciais fictícias: o LocalStack não valida.
    private static final StaticCredentialsProvider CREDENCIAIS =
            StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));

    static { aquecerDynamo(); }

    /** The first DynamoDB call boots DynamoDB Local (seconds); absorb it here, not inside the 5 s app client timeout. */
    private static void aquecerDynamo() {
        try (DynamoDbClient cliente = dynamo()) {
            cliente.listTables();
        }
    }

    public static DynamoDbClient dynamo() {
        return DynamoDbClient.builder().endpointOverride(LOCALSTACK.getEndpointOverride(DYNAMODB))
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(CREDENCIAIS).build();
    }

    public static SqsClient sqs() {
        return SqsClient.builder().endpointOverride(LOCALSTACK.getEndpointOverride(SQS))
                .region(Region.of(LOCALSTACK.getRegion())).credentialsProvider(CREDENCIAIS).build();
    }

    public static String criarFila(String nome) {
        try (SqsClient sqs = sqs()) {
            return sqs.createQueue(CreateQueueRequest.builder().queueName(nome)
                    .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true")).build()).queueUrl();
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry r) {
        r.add("execucao.modo", () -> "LOCALSTACK");
        r.add("execucao.dynamodb.region", LOCALSTACK::getRegion);
        r.add("execucao.dynamodb.endpoint", () -> LOCALSTACK.getEndpointOverride(DYNAMODB).toString());
        r.add("execucao.dynamodb.tabela-execucoes", () -> PREFIXO_CONTEXTO + "-execucoes");
        r.add("execucao.dynamodb.tabela-outbox", () -> PREFIXO_CONTEXTO + "-execucao-outbox");
        r.add("execucao.dynamodb.tabela-inbox", () -> PREFIXO_CONTEXTO + "-execucao-inbox");
        r.add("execucao.dynamodb.criar-tabelas", () -> "true");
        r.add("execucao.mensageria.region", LOCALSTACK::getRegion);
        r.add("execucao.mensageria.endpoint", () -> LOCALSTACK.getEndpointOverride(SQS).toString());
        TokensTeste.propriedades(r);
    }
}
