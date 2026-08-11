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
 * Read-only replica of the CRM suppression list, fed exclusively by
 * {@code customer.events.v1} (ADR-0044 R3, Story #1140).
 *
 * <p>The send worker checks suppression once per recipient. Doing that as a synchronous call
 * into pos-customer would put a network round trip in the inner loop of every campaign and
 * make CRM availability a hard dependency of sending — so the facts are replicated instead
 * and consulted locally. pos-customer remains the owner; nothing here may write this table
 * except the event consumer.
 *
 * <p>Keyed by {@code channel:addressHash} rather than party, matching the owner's semantics:
 * a bounced mailbox stays blocked no matter which party it later attaches to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "ext_suppression",
        indexes = {@Index(name = "idx_ext_suppression_party", columnList = "party_id")})
public class SuppressionReplica {

    /** {@code CHANNEL:addressHash} — the owner's natural key, carried verbatim. */
    @Id
    @Column(name = "suppression_key", nullable = false, length = 80, updatable = false)
    private String suppressionKey;

    @Column(name = "channel", nullable = false, length = 20)
    private String channel;

    @Column(name = "address_hash", nullable = false, length = 64)
    private String addressHash;

    @Column(name = "party_id", columnDefinition = "UUID")
    private UUID partyId;

    @Column(name = "reason", length = 30)
    private String reason;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static String keyOf(String channel, String addressHash) {
        return channel + ":" + addressHash;
    }

    /**
     * Explicit dependency hooks for the ArchUnit UUIDv7 rules (ADR-0013). This replica's id is
     * the owner's natural key, not a generated one, so the rules have nothing else to latch onto.
     */
    @Transient
    public Class<?>[] uuidv7Dependency() {
        return new Class<?>[] {UUIDv7Id.class, UUIDv7Generator.class};
    }
}
