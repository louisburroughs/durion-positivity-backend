package com.positivity.supplier.internal.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.entity.AuditAccessEntity;
import com.positivity.supplier.internal.enums.AuditAccessKind;
import com.positivity.supplier.internal.enums.AuditPayloadOutcome;
import com.positivity.supplier.internal.repository.AuditAccessRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records that someone read an exchange payload (ADR-0050 §7: "who read which exchange, when").
 *
 * <h2>This one FAILS CLOSED — and that makes it the odd one out of three</h2>
 *
 * pos-supplier now has three deliberately <em>different</em> failure semantics for three
 * write-alongside-work concerns. They are easy to mistake for each other, so all three are documented
 * at all three sites:
 *
 * <ol>
 *   <li>{@link ExchangeAuditObserver} — {@code REQUIRES_NEW}, <strong>swallows</strong> its failures.
 *       A vendor exchange must not fail because the audit sink is down.
 *   <li>{@link SupplierScheduleCoordinator#runPage} — {@code REQUIRED}, <strong>rolls back with its
 *       page</strong>. A checkpoint that commits independently silently skips a window.
 *   <li><strong>This class</strong> — {@code MANDATORY}, <strong>fails the read</strong>. ADR-0050 §7
 *       makes the access record a <em>precondition</em> of access, not a side effect of it. If the row
 *       cannot be written, the caller must get nothing: the alternative failure mode is someone reading
 *       decrypted commercial payloads with no record that they did, which is precisely what §7 exists
 *       to prevent.
 * </ol>
 *
 * <p>{@code MANDATORY} rather than {@code REQUIRED} is deliberate: it makes it impossible to call this
 * outside a transaction by accident. Without an enclosing transaction the row could commit on its own
 * while the read that follows fails, or worse, the read could succeed while this silently ran in its own
 * committed unit — either way the atomic "recorded if and only if served" property is lost.
 *
 * <h2>Over-recording is the correct failure direction</h2>
 *
 * Because the row commits with the read's transaction, a failure <em>after</em> commit but during
 * response serialization leaves a record saying a read happened when no bytes reached the client. That
 * is deliberate and is not compensated for: the safe asymmetry is to over-record rather than
 * under-record, exactly as {@link PayloadRedactor} applies unconditionally rather than sniffing the
 * format. Compensation logic would introduce a window in which a genuine read is unrecorded, trading a
 * harmless false positive for the one outcome §7 forbids.
 *
 * <h2>An unreadable payload must not roll this row back</h2>
 *
 * "Recorded if and only if served" has one boundary case that is easy to get backwards. When the stored
 * envelope cannot be decrypted, the read <em>did</em> happen — an authorised caller reached for commercial
 * content — and it is the single most interesting thing this table can record, because it may be evidence
 * of tampering or of a mishandled key rotation. But the read path answers that with an error response, and
 * an exception propagating out of the enclosing transaction would take this row with it.
 *
 * <p>{@code SupplierExchangeAuditServiceImpl.readPayload} therefore declares
 * {@code noRollbackFor = PayloadUnreadableException.class}. Read that exception as "the read happened and
 * produced nothing usable", never as "the read did not happen".
 */
@Service
public class AuditAccessRecorder {

    private final AuditAccessRepository accessRepository;
    private final Clock clock;

    public AuditAccessRecorder(@NonNull AuditAccessRepository accessRepository, @NonNull Clock clock) {
        this.accessRepository = Objects.requireNonNull(accessRepository, "accessRepository");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Records a payload read, in the caller's transaction.
     *
     * <p>Deliberately does <strong>not</strong> catch anything. A failure here propagates and rolls the
     * read back, which is the entire point.
     *
     * @param exchangeAuditId the exchange whose payload was read
     * @param vendorProfileId denormalised so the row is interpretable without a join
     * @param capability denormalised for the same reason
     * @param payloadOutcome what the read yielded; a failed or empty read is still an access
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPayloadRead(
            @NonNull UUID exchangeAuditId,
            @NonNull UUID vendorProfileId,
            @NonNull SupplierCapability capability,
            @NonNull AuditPayloadOutcome payloadOutcome) {
        Objects.requireNonNull(exchangeAuditId, "exchangeAuditId must not be null");
        Objects.requireNonNull(vendorProfileId, "vendorProfileId must not be null");
        Objects.requireNonNull(capability, "capability must not be null");
        Objects.requireNonNull(payloadOutcome, "payloadOutcome must not be null");

        AuditAccessEntity row = AuditAccessEntity.builder()
                .exchangeAuditId(exchangeAuditId)
                .vendorProfileId(vendorProfileId)
                .capability(capability)
                // Subject from the security context, never a parameter: an actor a caller could supply
                // is not an audit record, it is a claim.
                .accessedBy(SecurityContextHelper.getCurrentUsernameOrDefault("unknown"))
                .accessedAt(Instant.now(clock))
                .accessKind(AuditAccessKind.PAYLOAD_READ)
                .payloadOutcome(payloadOutcome)
                // No correlation id, deliberately: SupplierCorrelationContext scopes an OUTBOUND vendor
                // exchange and is not in flight during an inbound audit read, so reading it here would
                // record null on every row and misrepresent that as "no correlation". See the V4 migration
                // for the omission and the follow-up it names.
                .build();

        // saveAndFlush, not save: flushing here surfaces a constraint or connectivity failure NOW,
        // inside the read's transaction, rather than at commit after the payload has been handed to
        // the serializer. Failing late would still roll back, but the payload would already have been
        // read into the response object.
        accessRepository.saveAndFlush(row);
    }
}
