package com.positivity.platformsender.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.platformsender.internal.service.TestSenderProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpointsmsvoicev2.PinpointSmsVoiceV2Client;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sqs.SqsClient;

/** The AWS clients build offline, in the configured region; nothing here reaches AWS. */
@DisplayName("AwsClientConfig — clients in the configured region")
class AwsClientConfigTest {

    private final AwsClientConfig config = new AwsClientConfig();

    @Test
    void buildsEveryClientInTheConfiguredRegion() {
        try (SesV2Client ses = config.sesV2Client(TestSenderProperties.defaults());
                PinpointSmsVoiceV2Client sms = config.smsClient(TestSenderProperties.defaults());
                SqsClient sqs = config.outcomesSqsClient(TestSenderProperties.defaults())) {
            assertThat(ses.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
            assertThat(sms.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
            assertThat(sqs.serviceClientConfiguration().region()).isEqualTo(Region.US_EAST_1);
            assertThat(ses.serviceClientConfiguration()
                            .overrideConfiguration()
                            .retryStrategy()
                            .orElseThrow()
                            .maxAttempts())
                    .as("sending is not idempotent at the provider: one attempt, no SDK retry")
                    .isEqualTo(1);
            assertThat(sms.serviceClientConfiguration()
                            .overrideConfiguration()
                            .retryStrategy()
                            .orElseThrow()
                            .maxAttempts())
                    .isEqualTo(1);
        }
    }
}
