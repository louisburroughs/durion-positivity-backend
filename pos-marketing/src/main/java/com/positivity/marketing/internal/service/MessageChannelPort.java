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
 * reaches this module through the FI-2 contract (durion#369, {@code docs/PLATFORM_SENDER_CONTRACT.md}).
 *
 * <p>Two implementations exist, selected by {@code pos.marketing.send.transport}:
 * {@link LoggingMessageChannel} ({@code stub}, the default) and
 * {@link com.positivity.marketing.internal.client.PlatformSenderClient} ({@code platform-sender}).
 */
public interface MessageChannelPort {

    /**
     * Deliver one rendered message.
     *
     * @return the outcome, including a provider message id to correlate later delivery events
     */
    @NonNull
    SendOutcome send(@NonNull OutboundMessage message);

    /**
     * Everything the transport needs for one recipient.
     *
     * @param campaignSendId the send record's id — doubles as the client-side idempotency key
     *     under FI-2, so a retried HTTP call can never become a second email
     * @param recipientPartyId party to contact; the sender resolves the address under FI-2, so
     *     this module never holds one
     * @param contactId specific contact within the party, when audience resolution picked one
     * @param campaignCode stable campaign code, carried as sender metadata so outcomes and
     *     provider-side logs can name the campaign without a lookup
     */
    record OutboundMessage(
            @NonNull UUID campaignSendId,
            @NonNull CampaignChannel channel,
            @NonNull UUID recipientPartyId,
            @Nullable UUID contactId,
            @NonNull String campaignCode,
            @Nullable String subject,
            @NonNull String body) {}

    /**
     * @param accepted whether the provider took the message
     * @param providerMessageId correlation id for delivery, bounce, and complaint callbacks
     * @param addressHash SHA-256 of the normalized address the sender resolved, when it shares
     *     one — stored on the send record so a later bounce can be correlated without this
     *     module ever holding the raw address
     * @param failureReason why the provider refused, when it did
     * @param retryable whether a later attempt could succeed — a throttle is retryable, a
     *     malformed address is not, and retrying the latter forever just burns quota
     */
    record SendOutcome(
            boolean accepted,
            @Nullable String providerMessageId,
            @Nullable String addressHash,
            @Nullable String failureReason,
            boolean retryable) {

        public static SendOutcome accepted(String providerMessageId) {
            return accepted(providerMessageId, null);
        }

        public static SendOutcome accepted(String providerMessageId, @Nullable String addressHash) {
            return new SendOutcome(true, providerMessageId, addressHash, null, false);
        }

        public static SendOutcome permanentFailure(String reason) {
            return new SendOutcome(false, null, null, reason, false);
        }

        public static SendOutcome transientFailure(String reason) {
            return new SendOutcome(false, null, null, reason, true);
        }
    }
}
