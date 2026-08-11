package com.positivity.supplier.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.enums.PayloadCaptureLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * One outbound supplier <em>attempt</em> (ADR-0050 §7) — including failures, each individual retry,
 * and attempts suppressed by an open breaker. Written by {@code ExchangeAuditObserver}, which is an
 * {@code ExchangeObserver}, so the base client never depends on a repository.
 *
 * <h2>Immutable by intent</h2>
 *
 * An audit row is a record of something that happened, so it has no {@code @Version} and no
 * {@code updatedAt}/{@code updatedBy}: nothing updates it after insert except the retention purge,
 * which nulls the payload columns and stamps {@link #payloadsPurgedAt}. There is deliberately no
 * setter-driven "correct the audit" path.
 *
 * <h2>No foreign key to the profile</h2>
 *
 * Deliberate (binding decision): ADR-0050 §6/§7 require history to outlive configuration, and admin
 * {@code deleteProfile} is a hard delete. An FK would either block that delete or cascade it and
 * destroy commercial evidence. {@link #supplierRef} is therefore a <strong>snapshot as at the time of
 * the exchange</strong> and is descriptive only — it is renameable (ADR-0050 §1), so
 * {@link #vendorProfileId} is the identity for every query, grouping and metric.
 *
 * <h2>Payload columns</h2>
 *
 * {@code bytea} holding an AES-256-GCM envelope via {@link EncryptedPayloadConverter}. Nullable, and
 * null is a <em>normal</em> state: a {@code METADATA_ONLY} binding captures none, the vendor may send
 * no body, and the purge nulls them while keeping the metadata row. {@link #captureLevel} records
 * what was actually applied so a reader can tell those cases apart.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(
        name = "supplier_exchange_audit",
        indexes = {
            @Index(name = "idx_saudit_profile_started", columnList = "vendor_profile_id, started_at"),
            @Index(
                    name = "idx_saudit_profile_capability_started",
                    columnList = "vendor_profile_id, capability, started_at"),
            @Index(name = "idx_saudit_correlation", columnList = "correlation_id"),
            @Index(name = "idx_saudit_started_at", columnList = "started_at")
        })
@EntityListeners(AuditingEntityListener.class)
public class ExchangeAuditEntity {

    @Id
    @GeneratedValue
    @UUIDv7Id
    @Column(name = "exchange_audit_id", updatable = false, nullable = false)
    private UUID exchangeAuditId;

    /** Platform identity of the owning profile (ADR-0050 §1). The key for every query. */
    @Column(name = "vendor_profile_id", nullable = false, updatable = false)
    private UUID vendorProfileId;

    /**
     * The profile's alias as at the time of the exchange. Descriptive snapshot only — never a join
     * or filter key, because it is renameable and rows predate any rename.
     */
    @Column(name = "supplier_ref", nullable = false, updatable = false, length = 100)
    private String supplierRef;

    /**
     * Binding used. Every attempt the base client observes has one, because an unbound capability
     * fails at the resolver before any exchange exists. Kept nullable in the schema so a future
     * binding-less flow needs no migration, but nothing produces one today.
     */
    @Column(name = "binding_id", updatable = false)
    private UUID bindingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "capability", nullable = false, updatable = false, length = 64)
    private SupplierCapability capability;

    @Enumerated(EnumType.STRING)
    @Column(name = "protocol_family", nullable = false, updatable = false, length = 64)
    private ProtocolFamily protocolFamily;

    /**
     * Version key is data, not an enum (ADR-0051 §3).
     *
     * <p>64 to match {@code SupplierEndpointBindingEntity.protocolVersion}, the column this is copied from,
     * and widened by V5 from 32 after that mismatch was found. It is deliberately NOT truncated by the
     * writer, unlike the descriptive fields: this value selects a codec, so a shortened one would make the
     * row misreport which vendor norm actually built the document — present and wrong is worse than absent
     * and logged.
     */
    @Column(name = "protocol_version", nullable = false, updatable = false, length = 64)
    private String protocolVersion;

    @Column(name = "http_method", nullable = false, updatable = false, length = 16)
    private String httpMethod;

    /**
     * Absolute request URI, credential-redacted before storage.
     *
     * <p>This used to say "never carries credentials — they travel in headers, never the URI". That was true
     * of the three shipped auth strategies and enforced by nothing: vendors legitimately take query
     * parameters, and a binding path or a fourth strategy carrying {@code ?apikey=...} would have put a live
     * credential here. It matters because this column, like every metadata column, is stored unencrypted, is
     * retained after the 400-day purge has nulled the payloads (§7 keeps the metadata row), and is returned by
     * the metadata listing — so anything left in it is kept indefinitely and is readable by any
     * {@code supplier:audit:read} holder.
     *
     * <p>So the statement is now a fact about a control rather than a hope about callers:
     * {@code PayloadRedactor.redactUri} applies the form-field patterns to the query string, and
     * {@code METADATA_ONLY} drops the query string entirely, since that level promises to retain no content
     * and query parameters are content. The path is always kept — which endpoint was called is metadata.
     */
    @Column(name = "endpoint_uri", nullable = false, updatable = false, length = 2048)
    private String endpointUri;

    /** 1-based attempt number within one logical call, so retries are individually visible. */
    @Column(name = "attempt", nullable = false, updatable = false)
    private int attempt;

    @Column(name = "correlation_id", nullable = false, updatable = false, length = 100)
    private String correlationId;

    /** ADR-0052 §5 classification, stored as its name so the DB check constraint can pin it. */
    @Column(name = "outcome", nullable = false, updatable = false, length = 32)
    private String outcome;

    /** Response status; null when no response was received. */
    @Column(name = "http_status", updatable = false)
    private Integer httpStatus;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "duration_ms", nullable = false, updatable = false)
    private long durationMs;

    /** Operator-facing failure summary; never a credential. */
    @Column(name = "failure_detail", updatable = false, length = 2048)
    private String failureDetail;

    /**
     * The capture level actually applied. Recorded rather than re-derived from the binding, because
     * the binding's level can change afterwards and a reader must know what governed THIS row.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "capture_level", nullable = false, updatable = false, length = 16)
    private PayloadCaptureLevel captureLevel;

    /** Encrypted request payload; null for METADATA_ONLY, no body, or after purge. */
    @Convert(converter = EncryptedPayloadConverter.class)
    @Column(name = "request_payload")
    private String requestPayload;

    /** Encrypted response payload; null for METADATA_ONLY, no body, or after purge. */
    @Convert(converter = EncryptedPayloadConverter.class)
    @Column(name = "response_payload")
    private String responsePayload;

    /**
     * When the retention purge nulled the payloads. Distinguishes "purged" from "never captured",
     * which matters when someone asks why an old exchange has no body.
     */
    @Column(name = "payloads_purged_at")
    private Instant payloadsPurgedAt;

    /** Reserved per ADR-0051 §4; populated once codecs land (CAP-318). */
    @Column(name = "unmapped_fields", updatable = false, length = 4000)
    private String unmappedFields;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Audit actor (ADR-0018). A scheduler-driven exchange has no security context, so this is the
     * system actor supplied via {@code AuditActorContext}.
     */
    @CreatedBy
    @Column(name = "created_by", updatable = false, length = 255)
    private String createdBy;
}
