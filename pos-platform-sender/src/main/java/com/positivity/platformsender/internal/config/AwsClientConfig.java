package com.positivity.platformsender.internal.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * AWS SDK v2 clients, each built only when something uses it. Credentials come from the default
 * provider chain: the EC2 instance profile on the alpha host (the container reaches it through
 * IMDSv2, which needs a hop limit of 2), or the standard {@code AWS_*} environment variables
 * elsewhere. No key is ever configured in this module. The SES and SMS clients run without SDK
 * retries ({@link #noRetries()}); the SQS client keeps them, since receiving and deleting are safe to
 * repeat.
 */
@Configuration
public class AwsClientConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pos.platform-sender", name = "transport", havingValue = "aws")
    public SesV2Client sesV2Client(SenderProperties properties) {
        return SesV2Client.builder()
                .region(Region.of(properties.aws().region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(noRetries())
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pos.platform-sender", name = "transport", havingValue = "aws")
    public PinpointSmsVoiceV2Client smsClient(SenderProperties properties) {
        return PinpointSmsVoiceV2Client.builder()
                .region(Region.of(properties.aws().region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .overrideConfiguration(noRetries())
                .build();
    }

    /**
     * Sending is not idempotent at the provider (neither API takes a client token), so an SDK retry
     * after a read timeout could deliver twice. The send path retries through its caller instead,
     * and only when the attempt is known not to have been delivered ({@code AwsMessageTransport}).
     */
    static ClientOverrideConfiguration noRetries() {
        return ClientOverrideConfiguration.builder()
                .retryStrategy(AwsRetryStrategy.doNotRetry())
                .build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "pos.platform-sender.outcomes", name = "enabled", havingValue = "true")
    public SqsClient outcomesSqsClient(SenderProperties properties) {
        return SqsClient.builder()
                .region(Region.of(properties.aws().region()))
                .credentialsProvider(DefaultCredentialsProvider.builder().build())
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .build();
    }
}
