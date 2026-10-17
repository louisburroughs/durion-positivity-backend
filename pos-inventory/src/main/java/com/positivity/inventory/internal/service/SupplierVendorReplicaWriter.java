package com.positivity.inventory.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.inventory.internal.entity.ExtSupplierVendorReplica;
import com.positivity.inventory.internal.enums.VendorStatus;
import com.positivity.inventory.internal.repository.ExtSupplierVendorReplicaRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies one {@code supplier.vendor.updated} fact to pos-inventory's vendor copy (CAP:550 S36, #2525; ADR-0044 R3).
 *
 * <p>Called by {@link SupplierEventsListener}, the module's one {@code supplier.events.v1} consumer, inside its
 * handler transaction, so the copy row and the processed mark commit together.
 *
 * <h2>Schema version (Security ruling on #2617; ADR-0072)</h2>
 *
 * Version 1 of the fact carried a full tax-registration number. The envelope's {@code schemaVersion} is read before
 * the payload is mapped, and a fact below version 2 is skipped: nothing is copied, the listener still marks it
 * processed (owner {@code supplier}, so the supplier manifest still counts it), and {@value #SKIPPED_METRIC} counts
 * it with the tags {@code eventType} and {@code schemaVersion} only. Its payload is never logged, at any level; the
 * skip line carries the event id, the event type and the schema version and nothing else. Seeding comes from
 * pos-supplier's {@code POST /v1/supplier/vendors/facts/replay}, which re-emits current state at version 2.
 *
 * <h2>What is copied</h2>
 *
 * The ADR-0044 R3 minimum a purchase suggestion needs: the vendor id, its number, its display name, its status and when the
 * status changed, with the envelope's {@code aggregateVersion}. A fact applies when its version is at least the stored
 * one ({@link ReplicaVersionGuard}): an older fact arriving late changes nothing, and a replay at the same version
 * re-applies harmlessly. Tax registrations, the remit-to and payment terms stay out of this module.
 */
@Slf4j
@Component
public class SupplierVendorReplicaWriter {

    /** Counter of vendor facts skipped for their schema version; tags {@code eventType}, {@code schemaVersion}. */
    static final String SKIPPED_METRIC = "inventory.supplier_vendor.skipped";

    /** The lowest schema version this copy applies (#2621). */
    static final int MIN_SCHEMA_VERSION = SupplierVendorUpdatedV1.SCHEMA_VERSION;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ExtSupplierVendorReplicaRepository vendors;
    private final @Nullable MeterRegistry meterRegistry;

    public SupplierVendorReplicaWriter(
            Clock clock,
            ObjectMapper objectMapper,
            ExtSupplierVendorReplicaRepository vendors,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.vendors = vendors;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /**
     * Applies the fact, or skips it when its schema version is below {@value #MIN_SCHEMA_VERSION}. The caller marks
     * the event processed either way.
     */
    public void apply(@NonNull JsonNode envelope, @NonNull String eventId) {
        long schemaVersion = envelope.path("schemaVersion").longValue(0L);
        if (schemaVersion < MIN_SCHEMA_VERSION) {
            countSkipped(schemaVersion);
            log.info(
                    "Skipping supplier vendor fact below schema version {} eventId={} eventType={} schemaVersion={}",
                    MIN_SCHEMA_VERSION,
                    eventId,
                    SupplierVendorUpdatedV1.EVENT_TYPE,
                    schemaVersion);
            return;
        }

        SupplierVendorUpdatedV1 fact;
        try {
            fact = objectMapper.treeToValue(envelope.path("payload"), SupplierVendorUpdatedV1.class);
        } catch (DatabindException e) {
            // ADR-0072: the parser's message can quote the payload. What propagates names the event and the failure
            // class only, with no cause, so neither the listener's log line nor a DLQ record carries vendor data.
            throw new IllegalArgumentException("Malformed supplier.vendor.updated payload (eventId " + eventId + "): "
                    + e.getClass().getSimpleName());
        }
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtSupplierVendorReplica existing = vendors.findById(fact.vendorId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Ignoring stale supplier vendor fact eventId={} vendorId={} held={} incoming={}",
                    eventId,
                    fact.vendorId(),
                    existing.getAggregateVersion(),
                    aggregateVersion);
            return;
        }

        ExtSupplierVendorReplica row = existing != null
                ? existing
                : ExtSupplierVendorReplica.builder().vendorId(fact.vendorId()).build();
        row.setVendorNumber(fact.vendorNumber());
        row.setDisplayName(fact.displayName());
        row.setStatus(fact.isActive() ? VendorStatus.ACTIVE : VendorStatus.INACTIVE);
        row.setStatusChangedAt(fact.statusChangedAt());
        row.setAggregateVersion(aggregateVersion);
        row.setUpdatedAt(Instant.now(clock));
        try {
            // Flushed here, inside the handler transaction, so a refusal surfaces as this exception and not at commit.
            vendors.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // ADR-0072: the driver's DETAIL ("Failing row contains (...)") quotes every column of the refused row. The
            // exception that propagates (and that a DLQ record carries) names the vendor number, the event and the
            // constraint only, with no cause. logServerErrorDetail=false keeps the detail out of the driver's own
            // message, which Hibernate logs before Spring translates it.
            throw new DataIntegrityViolationException("The vendor copy refused vendor " + fact.vendorNumber()
                    + " (eventId " + eventId + "): " + constraintName(e));
        }
        log.info(
                "Copied supplier vendor vendorId={} vendorNumber={} status={} version={}",
                fact.vendorId(),
                fact.vendorNumber(),
                row.getStatus(),
                aggregateVersion);
    }

    private static String constraintName(DataIntegrityViolationException e) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && violation.getConstraintName() != null) {
                return "constraint " + violation.getConstraintName();
            }
            cause = cause.getCause();
        }
        return "a constraint violation";
    }

    private void countSkipped(long schemaVersion) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(SKIPPED_METRIC)
                .description("supplier.vendor.updated facts below the applied schema version, marked and skipped")
                .tag("eventType", SupplierVendorUpdatedV1.EVENT_TYPE)
                .tag("schemaVersion", Long.toString(schemaVersion))
                .register(meterRegistry)
                .increment();
    }
}
