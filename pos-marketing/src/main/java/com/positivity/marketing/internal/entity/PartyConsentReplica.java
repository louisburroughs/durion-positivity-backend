package com.positivity.marketing.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only replica of a party's resolved marketing eligibility, fed exclusively by
 * {@code customer.events.v1} (ADR-0044 R3, Story #1138).
 *
 * <p>Stores the CRM's <em>decision</em>, not its inputs. The commercial rule (account master
 * gate, else the primary business contact's consent) belongs to pos-customer; re-deriving it
 * here would guarantee the two copies drift.
 *
 * <p>{@code decidedAt} exists so the send worker can refuse to act on stale data. Consent is
 * the one replicated signal where being out of date means contacting someone who asked not to
 * be, so a row older than the configured bound is treated as "may not send" rather than
 * trusted — see {@code CampaignSendWorker}.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ext_party_consent",
        indexes = {@Index(name = "idx_ext_party_consent_decided", columnList = "decided_at")})
public class PartyConsentReplica {

    /** {@code partyId:CHANNEL} — the owner's natural key for a per-channel decision. */
    @Id
    @Column(name = "consent_key", nullable = false, length = 60, updatable = false)
    private String consentKey;

    @Column(name = "party_id", columnDefinition = "UUID", nullable = false)
    private UUID partyId;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "allowed", nullable = false)
    private boolean allowed;

    @Column(name = "reason", nullable = false, length = 40)
    private String reason;

    @Column(name = "governing_party_id", columnDefinition = "UUID")
    private UUID governingPartyId;

    /** When the owner made this decision. Drives the send worker's staleness check. */
    @Column(name = "decided_at", nullable = false)
    private Instant decidedAt;

    public static String keyOf(UUID partyId, String channel) {
        return partyId + ":" + channel;
    }

    /** Explicit dependency hooks for the ArchUnit UUIDv7 rules; the id is the owner's natural key. */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
