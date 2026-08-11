package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * The two payload columns of {@code supplier_exchange_audit} as <strong>raw stored bytes</strong> — a
 * second, read-only mapping of the same table.
 *
 * <h2>Why a second entity instead of reading {@link ExchangeAuditEntity}</h2>
 *
 * {@link ExchangeAuditEntity} maps these columns through {@link EncryptedPayloadConverter}, so
 * decryption happens inside Hibernate's <em>hydration</em> of that entity. On the audit read path that
 * is the wrong place for it to happen, for a reason that is easy to miss:
 *
 * <p>ADR-0050 §7 requires a payload read to be recorded <em>including when the payload turns out to be
 * unreadable</em> — a rotated-away key or a failed GCM tag is exactly the event an investigator needs to
 * see. If the decrypt failure were raised from inside {@code findById}, it would be raised from inside
 * the persistence context, leaving the session in a state no longer safe to write through, and the
 * identity needed to write the access row would be trapped inside the load that just failed. The access
 * record would be lost precisely when it matters most.
 *
 * <p>Loading raw bytes here and calling {@link AuditPayloadCipher#decrypt(byte[])} explicitly in the
 * service moves the failure into ordinary application code: an exception with the session untouched, so
 * the access row still writes and still commits.
 *
 * <p><strong>Never add {@code @Convert} to the fields below.</strong> Doing so would reintroduce exactly
 * the hydration-time decryption this class exists to avoid, and the loss of the access record would be
 * silent — no test of the happy path would notice.
 *
 * <p>{@code @Immutable} because this is a read model. Writes go through {@link ExchangeAuditEntity},
 * which is the only mapping that encrypts.
 */
@Getter
@NoArgsConstructor
@Entity
@Immutable
@Table(name = "supplier_exchange_audit")
public class ExchangeAuditRawPayloadEntity {

    /**
     * Shared identity with {@link ExchangeAuditEntity}; never generated here — this mapping only reads.
     *
     * <p>{@code @UUIDv7Id} is declared because the fleet-wide ADR-0013 rule requires it of every UUID
     * {@code @Id}, and it is inert on this class: {@code @Immutable} means nothing is ever inserted through
     * this mapping, so the generator has no code path to run on. Ids are minted by
     * {@link ExchangeAuditEntity}, which is the only mapping that writes.
     */
    @Id
    @UUIDv7Id
    @Column(name = "exchange_audit_id", updatable = false, nullable = false)
    private UUID exchangeAuditId;

    /** Stored AES-256-GCM envelope, or {@code null} for METADATA_ONLY, no body, or after purge. */
    @Column(name = "request_payload", updatable = false)
    private byte @Nullable [] requestPayload;

    /** Stored AES-256-GCM envelope, or {@code null} for METADATA_ONLY, no body, or after purge. */
    @Column(name = "response_payload", updatable = false)
    private byte @Nullable [] responsePayload;
}
