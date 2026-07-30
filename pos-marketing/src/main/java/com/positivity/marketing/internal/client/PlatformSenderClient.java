package com.positivity.marketing.internal.client;

import com.positivity.marketing.internal.service.MessageChannelPort;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Adapter to the shared platform sender (Story #1150, FI-2 contract — durion#369,
 * {@code docs/PLATFORM_SENDER_CONTRACT.md}).
 *
 * <p>The sender owns provider credentials, address resolution, wire-level retries, and the
 * bounce/complaint webhooks (decision O-1); this adapter only hands over one rendered message
 * and reports whether it was accepted. Outcomes come back asynchronously on
 * {@code sender.outcomes.v1} and are applied by
 * {@link com.positivity.marketing.internal.service.DeliveryOutcomeListener}.
 *
 * <p>{@code campaignSendId} travels as the {@code messageId} idempotency key, so the send
 * worker retrying a transient failure can never produce a duplicate email: the sender answers
 * a replayed key with the original {@code providerMessageId} (HTTP 200 instead of 202).
 *
 * <p>Failure mapping follows the port's retry semantics: a 4xx is a permanent refusal
 * (malformed message, unknown party, no resolvable address — retrying cannot fix it), while a
 * 5xx or an I/O failure is transient and left to the worker's bounded retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.marketing.send", name = "transport", havingValue = "platform-sender")
public class PlatformSenderClient implements MessageChannelPort {

    private final RestClient restClient;

    public PlatformSenderClient(
            RestClient.Builder restClientBuilder,
            @Value("${pos.marketing.sender.base-url}") String baseUrl,
            @Value("${pos.marketing.sender.api-secret:}") String apiSecret) {
        RestClient.Builder builder = restClientBuilder.clone().baseUrl(baseUrl);
        if (!apiSecret.isBlank()) {
            builder.defaultHeader("X-Pos-Sender-Secret", apiSecret);
        }
        this.restClient = builder.build();
    }

    @Override
    public @NonNull SendOutcome send(@NonNull OutboundMessage message) {
        SendMessageRequest request = new SendMessageRequest(
                message.campaignSendId(),
                message.channel().name(),
                message.recipientPartyId(),
                message.contactId(),
                message.campaignCode(),
                message.subject(),
                message.body());
        try {
            SendMessageResponse response = restClient
                    .post()
                    .uri("/platform-sender/v1/messages")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(SendMessageResponse.class);
            if (response == null || response.providerMessageId() == null) {
                // An accepting sender that returns no correlation id leaves every later
                // outcome unmatchable — treat it as a contract violation worth retrying.
                return SendOutcome.transientFailure("Sender returned no providerMessageId");
            }
            return SendOutcome.accepted(response.providerMessageId(), response.addressHash());
        } catch (RestClientResponseException e) {
            String reason = "Sender rejected message: HTTP " + e.getStatusCode().value();
            if (e.getStatusCode().is5xxServerError()) {
                return SendOutcome.transientFailure(reason);
            }
            log.warn(
                    "Platform sender permanently refused send {} ({}): {}",
                    message.campaignSendId(),
                    e.getStatusCode().value(),
                    e.getResponseBodyAsString());
            return SendOutcome.permanentFailure(reason);
        } catch (ResourceAccessException e) {
            return SendOutcome.transientFailure("Sender unreachable: " + e.getMessage());
        }
    }

    /** FI-2 send request; {@code messageId} is the idempotency key (= campaignSendId). */
    record SendMessageRequest(
            @NonNull UUID messageId,
            @NonNull String channel,
            @NonNull UUID recipientPartyId,
            @Nullable UUID contactId,
            @NonNull String campaignCode,
            @Nullable String subject,
            @NonNull String body) {}

    /** FI-2 send response; {@code addressHash} is optional — not every channel shares it. */
    record SendMessageResponse(
            @Nullable String providerMessageId, @Nullable String addressHash) {}
}
