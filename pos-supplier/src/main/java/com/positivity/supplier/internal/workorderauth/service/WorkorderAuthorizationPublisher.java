package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierWorkorderAuthDeniedV1;
import com.positivity.domainevents.supplier.SupplierWorkorderAuthGrantedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.SupplierWorkorderAuthorizationEntity;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Publishes fleet authorization decisions (CAP-323 #1229, ADR-0044 R1).
 *
 * <h2>Only the vendor's answers are published</h2>
 *
 * {@code GRANTED} and {@code DENIED} go out. {@code PENDING} does not — there is nothing to say
 * yet. {@code MANUAL_REVIEW} does not, and that is the load-bearing decision here: it means "we
 * could not get an answer", and publishing it would put a non-answer onto a topic whose consumers
 * are deciding whether work may proceed. A shop that hears "denied" acts very differently from one
 * that hears nothing, and only one of those is honest about an unreachable vendor.
 *
 * <h2>{@code NOT_FOUND} is published as a denial</h2>
 *
 * With the vendor's own reason attached, unchanged. The vendor did answer — it said it does not know
 * this vehicle or contract — and the practical consequence for a shop is the same as a refusal: do
 * not proceed under the fleet contract. The reason text is what tells a service advisor that this
 * one is fixable by correcting a fleet number rather than by phoning the fleet.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkorderAuthorizationPublisher {

    private static final String SOURCE = "pos-supplier";

    private final SupplierOutboxEventWriter outboxEventWriter;

    /**
     * Publishes the decision on a row, when there is one to publish.
     *
     * @param row the authorization as stored
     * @param occurredAt when the decision was observed
     */
    public void publishIfTerminal(@NonNull SupplierWorkorderAuthorizationEntity row, @NonNull Instant occurredAt) {
        switch (row.getStatus()) {
            case GRANTED ->
                publish(
                        SupplierWorkorderAuthGrantedV1.EVENT_TYPE,
                        SupplierWorkorderAuthGrantedV1.SCHEMA_VERSION,
                        row,
                        occurredAt,
                        new SupplierWorkorderAuthGrantedV1(
                                row.getVendorProfileId(),
                                row.getSupplierRef(),
                                row.getWorkorderId(),
                                row.getVendorAuthorizationId(),
                                row.getContractReference(),
                                row.getAuthorizedAmount(),
                                row.getCurrency(),
                                occurredAt));
            case DENIED, NOT_FOUND ->
                publish(
                        SupplierWorkorderAuthDeniedV1.EVENT_TYPE,
                        SupplierWorkorderAuthDeniedV1.SCHEMA_VERSION,
                        row,
                        occurredAt,
                        new SupplierWorkorderAuthDeniedV1(
                                row.getVendorProfileId(),
                                row.getSupplierRef(),
                                row.getWorkorderId(),
                                row.getVendorAuthorizationId(),
                                row.getReasonCode(),
                                row.getReasonText(),
                                occurredAt));
            case PENDING, MANUAL_REVIEW -> {
                // Nothing to say. See the class note: a non-answer must not reach consumers that
                // are deciding whether work may proceed.
            }
        }
    }

    private void publish(
            @NonNull String eventType,
            int schemaVersion,
            @NonNull SupplierWorkorderAuthorizationEntity row,
            @NonNull Instant occurredAt,
            @NonNull Object payload) {
        outboxEventWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        eventType,
                        schemaVersion,
                        // Keyed on the authorization row, so every decision about one workorder
                        // lands on one partition in order. A grant overtaking the denial it
                        // replaced would authorise work the fleet had already refused.
                        row.getSupplierWorkorderAuthorizationId(),
                        0L,
                        occurredAt,
                        SOURCE,
                        null,
                        SOURCE,
                        payload));
        log.info("Published {} for workorder {} at {}", eventType, row.getWorkorderId(), row.getSupplierRef());
    }
}
