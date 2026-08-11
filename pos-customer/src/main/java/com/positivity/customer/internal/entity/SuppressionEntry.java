package com.positivity.customer.internal.entity;

import com.positivity.customer.internal.enums.ConsentChangeSource;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SuppressionReason;
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
 * A hard, address-level block on marketing (Story #1140).
 *
 * <p>Suppression is keyed by a hash of the <em>normalized address</em>, not by party: a
 * bounced mailbox must stay blocked even if the address is later attached to a different
 * party, and the same mailbox shared by two contacts must block both. Storing only the
 * hash keeps the raw address out of this table while still supporting exact lookup —
 * {@code addressHint} carries a masked form for admin review.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "suppression_entry",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_suppression_channel_address",
                        columnNames = {"channel", "address_hash"}),
        indexes = {
            @Index(name = "idx_suppression_party", columnList = "party_id"),
            @Index(name = "idx_suppression_lookup", columnList = "channel, address_hash")
        })
public class SuppressionEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "suppression_id", columnDefinition = "UUID", nullable = false, updatable = false)
    private UUID suppressionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", columnDefinition = "VARCHAR(20)", nullable = false)
    private MarketingChannel channel;

    /** SHA-256 hex of the normalized address. */
    @Column(name = "address_hash", nullable = false, length = 64)
    private String addressHash;

    /** Masked address for admin review, e.g. {@code j***@example.com}. Never the raw value. */
    @Column(name = "address_hint", length = 120)
    private String addressHint;

    /** Best-effort link to the party the address belonged to when suppressed; may be null. */
    @Column(name = "party_id", columnDefinition = "UUID")
    private UUID partyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", columnDefinition = "VARCHAR(30)", nullable = false)
    private SuppressionReason reason;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", columnDefinition = "VARCHAR(30)", nullable = false)
    private ConsentChangeSource source;

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
