package com.positivity.marketing.internal.service;

import com.positivity.marketing.internal.enums.CampaignChannel;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Transport boundary for actually delivering a rendered message (Stories #1149/#1150).
 *
 * <p>pos-marketing owns orchestration only (decision O-1): who to contact, in what order, and
 * whether they may be contacted at all. The transport itself — provider credentials, retries at
 * the wire level, bounce and complaint webhooks — belongs to the shared platform sender, and
 * reaches this module through the FI-2 contract (durion#369).
 *
 * <p>Until that contract lands, {@link LoggingMessageChannel} stands in. Keeping the port
 * defined now means the orchestrator, the idempotency guarantees, and the send-time consent
 * re-check are all exercised and testable; only the final hop is stubbed.
 */
public interface MessageChannelPort {

    /**
     * Deliver one rendered message.
     *
     * @param recipientPartyId party to contact; the sender resolves the address under FI-2, so
     *     this module never holds one
     * @return the outcome, including a provider message id to correlate later delivery events
     */
    @NonNull
    SendOutcome send(
            @NonNull CampaignChannel channel,
            @NonNull UUID recipientPartyId,
            @Nullable String subject,
            @NonNull String body);

    /**
     * @param accepted whether the provider took the message
     * @param providerMessageId correlation id for delivery, bounce, and complaint callbacks
     * @param failureReason why the provider refused, when it did
     * @param retryable whether a later attempt could succeed — a throttle is retryable, a
     *     malformed address is not, and retrying the latter forever just burns quota
     */
    record SendOutcome(
            boolean accepted,
            @Nullable String providerMessageId,
            @Nullable String failureReason,
            boolean retryable) {

        public static SendOutcome accepted(String providerMessageId) {
            return new SendOutcome(true, providerMessageId, null, false);
        }

        public static SendOutcome permanentFailure(String reason) {
            return new SendOutcome(false, null, reason, false);
        }

        public static SendOutcome transientFailure(String reason) {
            return new SendOutcome(false, null, reason, true);
        }
    }
}
