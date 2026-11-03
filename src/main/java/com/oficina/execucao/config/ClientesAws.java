package com.oficina.execucao.config;

import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.SdkHttpClient;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.retries.StandardRetryStrategy;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.SqsClientBuilder;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.auth.StsWebIdentityTokenFileCredentialsProvider;
import software.amazon.awssdk.utils.IoUtils;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Owns every AWS client: IRSA web identity in the cluster, static test credentials against LocalStack. */
public final class ClientesAws implements AutoCloseable {
    private final List<AutoCloseable> recursos;
    private final DynamoDbClient dynamo;
    private final SqsClient sqsEnvio;
    private final SqsClient sqsRecepcao;

    private ClientesAws(List<AutoCloseable> recursos, DynamoDbClient dynamo, SqsClient sqsEnvio, SqsClient sqsRecepcao) {
        this.recursos = recursos;
        this.dynamo = dynamo;
        this.sqsEnvio = sqsEnvio;
        this.sqsRecepcao = sqsRecepcao;
    }

    public static ClientesAws criar(ExecucaoProperties p, String roleArn, String tokenFile) {
        List<AutoCloseable> recursos = new ArrayList<>();
        SdkHttpClient http = UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(1))
                .socketTimeout(Duration.ofSeconds(15)).build();
        recursos.add(http);
        ValidadorConfiguracaoAws.validar(p, roleArn, tokenFile);
        String dynamoEndpoint = p.dynamodb().endpoint();
        String sqsEndpoint = p.mensageria().endpoint();
        AwsCredentialsProvider irsa = null;
        if (p.modo() == ExecucaoProperties.Modo.IRSA) {
            // AssumeRoleWithWebIdentity is unsigned. Never recurse into the default credentials chain.
            StsClient sts = StsClient.builder().region(Region.of(p.dynamodb().region()))
                    .credentialsProvider(AnonymousCredentialsProvider.create()).httpClient(http)
                    .overrideConfiguration(limites(Duration.ofSeconds(2))).build();
            var provider = StsWebIdentityTokenFileCredentialsProvider.builder().stsClient(sts).roleArn(roleArn)
                    .roleSessionName("execution-service").webIdentityTokenFile(Path.of(tokenFile))
                    .asyncCredentialUpdateEnabled(false).build();
            recursos.add(provider);
            recursos.add(sts);
            irsa = provider;
        }
        DynamoDbClientBuilder dynamoBuilder = DynamoDbClient.builder().region(Region.of(p.dynamodb().region()))
                .httpClient(http).credentialsProvider(credenciais(dynamoEndpoint, irsa))
                .overrideConfiguration(limites(Duration.ofSeconds(5)));
        if (!vazio(dynamoEndpoint)) dynamoBuilder.endpointOverride(URI.create(dynamoEndpoint));
        DynamoDbClient dynamo = dynamoBuilder.build();
        SqsClient envio = sqs(p, http, credenciais(sqsEndpoint, irsa), Duration.ofSeconds(2));
        SqsClient recepcao = sqs(p, http, credenciais(sqsEndpoint, irsa), Duration.ofSeconds(15));
        recursos.add(0, dynamo);
        recursos.add(0, envio);
        recursos.add(0, recepcao);
        return new ClientesAws(recursos, dynamo, envio, recepcao);
    }

    private static SqsClient sqs(ExecucaoProperties p, SdkHttpClient http, AwsCredentialsProvider credenciais, Duration limite) {
        SqsClientBuilder builder = SqsClient.builder().region(Region.of(p.mensageria().region())).httpClient(http)
                .credentialsProvider(credenciais).overrideConfiguration(limites(limite));
        if (!vazio(p.mensageria().endpoint())) builder.endpointOverride(URI.create(p.mensageria().endpoint()));
        return builder.build();
    }

    private static AwsCredentialsProvider credenciais(String endpoint, AwsCredentialsProvider irsa) {
        return vazio(endpoint) ? irsa : StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    private static ClientOverrideConfiguration limites(Duration limite) {
        return ClientOverrideConfiguration.builder().apiCallTimeout(limite).apiCallAttemptTimeout(limite)
                .retryStrategy(StandardRetryStrategy.builder().maxAttempts(1).build()).build();
    }

    private static boolean vazio(String valor) { return valor == null || valor.isBlank(); }

    public DynamoDbClient dynamo() { return dynamo; }
    public SqsClient sqsEnvio() { return sqsEnvio; }
    public SqsClient sqsRecepcao() { return sqsRecepcao; }

    @Override public void close() {
        recursos.forEach(r -> IoUtils.closeQuietly(r, null));
    }
}
