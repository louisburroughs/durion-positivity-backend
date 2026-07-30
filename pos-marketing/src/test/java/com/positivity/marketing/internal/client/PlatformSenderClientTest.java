package com.positivity.marketing.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.service.MessageChannelPort;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Pins the FI-2 adapter's failure mapping (Story #1150): the send worker's retry policy is
 * only as good as the adapter's transient/permanent classification, and misclassifying a 4xx
 * as retryable would hammer the sender with a message it will never accept.
 */
class PlatformSenderClientTest {

    private static final UUID SEND_ID = UUID.fromString("01960005-0000-7000-8000-000000000070");
    private static final UUID PARTY = UUID.fromString("01960005-0000-7000-8000-000000000071");

    private MockRestServiceServer server;
    private PlatformSenderClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new PlatformSenderClient(builder, "http://sender", "secret-1");
    }

    private static MessageChannelPort.OutboundMessage message() {
        return new MessageChannelPort.OutboundMessage(
                SEND_ID, CampaignChannel.EMAIL, PARTY, null, "SPRING-2026", "Hello", "Body");
    }

    @Test
    @DisplayName("an accepted message carries the idempotency key and returns the provider id")
    void acceptedSend() {
        server.expect(requestTo("http://sender/platform-sender/v1/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("X-Pos-Sender-Secret", "secret-1"))
                .andExpect(jsonPath("$.messageId").value(SEND_ID.toString()))
                .andExpect(jsonPath("$.campaignCode").value("SPRING-2026"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"providerMessageId\":\"prov-1\",\"addressHash\":\"hash-1\"}"));

        MessageChannelPort.SendOutcome outcome = client.send(message());

        assertThat(outcome.accepted()).isTrue();
        assertThat(outcome.providerMessageId()).isEqualTo("prov-1");
        assertThat(outcome.addressHash()).isEqualTo("hash-1");
    }

    @Test
    @DisplayName("a 4xx is a permanent refusal — retrying cannot fix it")
    void clientErrorIsPermanent() {
        server.expect(requestTo("http://sender/platform-sender/v1/messages"))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"NO_RESOLVABLE_ADDRESS\"}"));

        MessageChannelPort.SendOutcome outcome = client.send(message());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.retryable()).isFalse();
        assertThat(outcome.failureReason()).contains("422");
    }

    @Test
    @DisplayName("a 5xx is transient and left to the worker's bounded retry")
    void serverErrorIsTransient() {
        server.expect(requestTo("http://sender/platform-sender/v1/messages"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));

        MessageChannelPort.SendOutcome outcome = client.send(message());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    @DisplayName("an unreachable sender is transient")
    void ioErrorIsTransient() {
        server.expect(requestTo("http://sender/platform-sender/v1/messages"))
                .andRespond(withException(new IOException("connection refused")));

        MessageChannelPort.SendOutcome outcome = client.send(message());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.retryable()).isTrue();
    }

    @Test
    @DisplayName("acceptance without a providerMessageId is a contract violation worth retrying")
    void missingProviderMessageIdIsTransient() {
        server.expect(requestTo("http://sender/platform-sender/v1/messages"))
                .andRespond(withStatus(HttpStatus.ACCEPTED)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{}"));

        MessageChannelPort.SendOutcome outcome = client.send(message());

        assertThat(outcome.accepted()).isFalse();
        assertThat(outcome.retryable()).isTrue();
    }
}
