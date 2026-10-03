package com.positivity.platformsender.internal.service;

import com.positivity.platformsender.internal.enums.MessageChannel;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The provider boundary. Two implementations, selected by {@code pos.platform-sender.transport}:
 * {@link LoggingMessageTransport} ({@code log}, the default) and
 * {@code com.positivity.platformsender.internal.client.AwsMessageTransport} ({@code aws}).
 *
 * <p>An implementation never throws for a provider answer; it classifies it. A permanent result is
 * one no retry can change (a refused address, a rejected message); a transient one is a failure the
 * provider answered, or one that happened before the request left (throttling, a provider 5xx, a
 * refused connection), which a later attempt may get past; an uncertain one is a request that may
 * have been delivered without an answer coming back, which must never be sent again.
 */
public interface MessageTransport {

    /**
     * Tag (SES) and context (SMS) keys every provider message carries, so its events can be relayed
     * under the right tenant and traced to the caller's {@code messageId}. SES allows only
     * {@code [A-Za-z0-9_-]} in tag names and values.
     */
    String TAG_TENANT_ID = "pos-tenant-id";

    String TAG_MESSAGE_ID = "pos-message-id";
    String TAG_CAMPAIGN = "pos-campaign";

    @NonNull
    TransportResult send(@NonNull OutboundMessage message);

    /**
     * @param messageId the caller's idempotency key, tagged on the provider message so its events
     *     can be traced back
     * @param tenantId the tenant the message is sent for, tagged on the provider message so its
     *     outcome events are relayed under the same tenant
     * @param address the normalized address (email, or E.164 phone number)
     */
    record OutboundMessage(
            @NonNull UUID messageId,
            @NonNull UUID tenantId,
            @NonNull MessageChannel channel,
            @NonNull String address,
            @NonNull String campaignCode,
            @Nullable String subject,
            @NonNull String body) {}

    /** What the provider did with the message. */
    record TransportResult(
            @NonNull Kind kind,
            @Nullable String providerMessageId,
            @Nullable String code,
            @Nullable String reason) {

        public enum Kind {
            ACCEPTED,
            PERMANENT_FAILURE,
            TRANSIENT_FAILURE,
            /**
             * The request may have reached the provider but no answer came back (a read timeout, a
             * connection lost mid-response): it may have been delivered. Never retried by us.
             */
            UNCERTAIN
        }

        public static @NonNull TransportResult accepted(@NonNull String providerMessageId) {
            return new TransportResult(Kind.ACCEPTED, providerMessageId, null, null);
        }

        public static @NonNull TransportResult permanentFailure(@NonNull String code, @NonNull String reason) {
            return new TransportResult(Kind.PERMANENT_FAILURE, null, code, reason);
        }

        public static @NonNull TransportResult transientFailure(@NonNull String code, @NonNull String reason) {
            return new TransportResult(Kind.TRANSIENT_FAILURE, null, code, reason);
        }

        public static @NonNull TransportResult uncertain(@NonNull String code, @NonNull String reason) {
            return new TransportResult(Kind.UNCERTAIN, null, code, reason);
        }
    }
}
