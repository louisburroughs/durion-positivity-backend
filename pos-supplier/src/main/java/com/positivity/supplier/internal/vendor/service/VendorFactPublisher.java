package com.positivity.supplier.internal.vendor.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.entity.VendorRemitTo;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Queues {@code supplier.vendor.updated} for a vendor's committed state (#2516, SPEC §4.9 "The
 * fact"), in the caller's transaction through the outbox (ADR-0044 §4): a vendor change is never
 * published without its committed state, and never committed without its fact.
 *
 * <p>The vendor must be flushed first, so {@code version} is the one the change produced: it is the
 * envelope's {@code aggregateVersion}. It advances with every committed change (a no-op update
 * publishes nothing); a replay re-sends the current version, so consumers apply a fact unless they
 * already hold a <em>newer</em> one ({@code ReplicaVersionGuard}: equal versions re-apply). The record key is {@code vendorId}. No bank details exist to publish
 * (OI-14), and a pending remit-to change is never on the vendor row, so it cannot leak here.
 */
@Component
@RequiredArgsConstructor
public class VendorFactPublisher {

    static final String SOURCE = "pos-supplier";

    private final SupplierOutboxEventWriter outboxEventWriter;

    /**
     * Queues the vendor's current state.
     *
     * @param vendor the flushed vendor
     * @param occurredAt when the change committed (or, for a replay, when it was re-sent)
     * @param actor the principal whose change this is
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(@NonNull SupplierVendorEntity vendor, @NonNull Instant occurredAt, @Nullable String actor) {
        Objects.requireNonNull(vendor.getVersion(), "vendor must be flushed before its fact is queued");
        outboxEventWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierVendorUpdatedV1.EVENT_TYPE,
                        SupplierVendorUpdatedV1.SCHEMA_VERSION,
                        vendor.getVendorId(),
                        vendor.getVersion(),
                        occurredAt,
                        SOURCE,
                        // tenantId: stamped by the outbox writer from the bound tenant (ADR-0062 §3)
                        null,
                        null,
                        actor,
                        toFact(vendor, occurredAt)));
    }

    @NonNull
    static SupplierVendorUpdatedV1 toFact(@NonNull SupplierVendorEntity vendor, @NonNull Instant occurredAt) {
        return new SupplierVendorUpdatedV1(
                vendor.getVendorId(),
                vendor.getVendorNumber(),
                vendor.getLegalName(),
                vendor.getDisplayName(),
                vendor.getTaxRegistrations().stream()
                        .map(registration -> new SupplierVendorUpdatedV1.TaxRegistration(
                                registration.scheme(), registration.number(), registration.region()))
                        .toList(),
                toFactRemitTo(vendor.getRemitTo()),
                vendor.getRemitToVersion(),
                vendor.getDefaultPaymentTerms(),
                vendor.getDefaultCurrency(),
                SupplierVendorUpdatedV1.Status.valueOf(vendor.getStatus().name()),
                vendor.getStatusChangedAt(),
                vendor.getStatusReason(),
                vendor.getRemitToChangedAt(),
                vendor.getRemitToRequestedBy(),
                vendor.getRemitToApprovedBy(),
                vendor.getCreatedBy(),
                vendor.getCreatedAt(),
                occurredAt);
    }

    private static SupplierVendorUpdatedV1.@Nullable RemitTo toFactRemitTo(@Nullable VendorRemitTo remitTo) {
        if (remitTo == null) {
            return null;
        }
        return new SupplierVendorUpdatedV1.RemitTo(
                remitTo.payeeName(),
                remitTo.addressLine1(),
                remitTo.addressLine2(),
                remitTo.city(),
                remitTo.region(),
                remitTo.postalCode(),
                remitTo.countryCode(),
                remitTo.remittanceEmail());
    }
}
