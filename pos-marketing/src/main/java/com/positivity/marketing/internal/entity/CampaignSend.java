package com.positivity.marketing.internal.entity;

import com.positivity.marketing.internal.enums.CampaignChannel;
import com.positivity.marketing.internal.enums.SendStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One recipient's send record for a campaign (Story #1149).
 *
 * <p>The {@code (campaign_id, recipient_party_id, channel)} uniqueness constraint is the
 * idempotency guarantee: re-invoking {@code /send}, retrying a batch, or replaying after a
 * crash can never contact the same person twice on the same channel for the same campaign.
 *
 * <p>Only {@code addressHash} is retained, never the resolved address. A send log that
 * accumulates every customer's email in plaintext becomes the most sensitive table in the
 * system for no operational benefit — the hash is enough to correlate a bounce back to a row.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "campaign_send",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_campaign_send_recipient",
                        columnNames = {"campaign_id", "recipient_party_id", "channel"}),
        indexes = {
            @Index(name = "idx_campaign_send_campaign", columnList = "campaign_id, status"),
            @Index(name = "idx_campaign_send_provider_message", columnList = "provider_message_id")
        })
public class CampaignSend {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "campaign_send_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID campaignSendId;

    @Column(name = "campaign_id", columnDefinition = "UUID", nullable = false)
    private UUID campaignId;

    @Column(name = "recipient_party_id", columnDefinition = "UUID", nullable = false)
    private UUID recipientPartyId;

    /** The contact addressed within the party, when one was resolved. */
    @Column(name = "contact_id", columnDefinition = "UUID")
    private UUID contactId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)", nullable = false)
    private CampaignChannel channel;

    /** SHA-256 of the normalized address; the raw address is never persisted. */
    @Column(name = "address_hash", length = 64)
    private String addressHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", columnDefinition = "VARCHAR(20)", nullable = false)
    @Builder.Default
    private SendStatus status = SendStatus.PENDING;

    /** Reason a send was suppressed or failed — the decision code from the CRM, or a provider error. */
    @Column(name = "failure_reason", length = 500)
    private String failureReason;

    @Column(name = "provider_message_id", length = 200)
    private String providerMessageId;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    /** First open reported by the sender; stays null when the channel can't track opens. */
    @Column(name = "opened_at")
    private Instant openedAt;

    /** First click reported by the sender; stays null when the channel can't track clicks. */
    @Column(name = "clicked_at")
    private Instant clickedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
