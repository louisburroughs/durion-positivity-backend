package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps an eye on held EDI invoices (CAP:550 S24, #2517; "Audit and observability"): per tenant, it releases the
 * {@code VENDOR_NOT_IN_COPY} holds whose vendor is copied by now (a release that failed when the vendor arrived),
 * refreshes the {@code accounting.supplier_invoice.held{reason}} gauge of open holds (every tenant's, summed), and once
 * a day WARN-logs the holds older than 24 hours, by event id, reason and age only. One tenant's failure does not stop
 * the others.
 */
@Slf4j
@Component
public class SupplierInvoiceHoldSweep {

    /** A hold older than this is WARN-logged by the daily pass. */
    static final Duration STALE = Duration.ofHours(24);

    private final SupplierInvoiceHoldRepository holds;
    /** The consumer that releases holds; absent where the Kafka rails are off (dev, test), then nothing is released. */
    private final ObjectProvider<SupplierEventsListener> listener;

    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final Map<SupplierInvoiceHold.Reason, AtomicLong> held = new EnumMap<>(SupplierInvoiceHold.Reason.class);

    public SupplierInvoiceHoldSweep(
            SupplierInvoiceHoldRepository holds,
            ObjectProvider<SupplierEventsListener> listener,
            TenantIterator tenantIterator,
            PlatformTransactionManager transactionManager,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.holds = holds;
        this.listener = listener;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        for (SupplierInvoiceHold.Reason reason : SupplierInvoiceHold.Reason.values()) {
            AtomicLong value = new AtomicLong();
            held.put(reason, value);
            if (registry != null) {
                Gauge.builder("accounting.supplier_invoice.held", value, AtomicLong::get)
                        .description("Open supplier invoice holds, by reason")
                        .tag("reason", reason.name())
                        .register(registry);
            }
        }
    }

    /** Releases what can be released and refreshes the gauge, for every active tenant. */
    @Scheduled(
            fixedDelayString = "${pos.accounting.supplier-invoice-hold.sweep-interval:PT1H}",
            initialDelayString = "${pos.accounting.supplier-invoice-hold.sweep-initial-delay:PT5M}")
    public void sweep() {
        Map<SupplierInvoiceHold.Reason, Long> totals = new EnumMap<>(SupplierInvoiceHold.Reason.class);
        tenantIterator.forEachActiveTenant(tenantId -> {
            List<UUID> releasable = transaction.execute(
                    _ -> holds.findCopiedVendorIdsWithOpenHolds(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY));
            SupplierEventsListener releaser = listener.getIfAvailable();
            if (releasable != null && releaser != null) {
                releasable.forEach(releaser::releaseHolds);
            }
            for (SupplierInvoiceHold.Reason reason : SupplierInvoiceHold.Reason.values()) {
                Long count = transaction.execute(_ -> holds.countByReasonAndReleasedAtIsNull(reason));
                totals.merge(reason, count == null ? 0L : count, Long::sum);
            }
        });
        totals.forEach((reason, count) -> held.get(reason).set(count));
    }

    /** The daily WARN of holds older than 24 hours. */
    @Scheduled(cron = "${pos.accounting.supplier-invoice-hold.stale-warn-cron:0 15 6 * * *}")
    public void warnStale() {
        Instant cutoff = Instant.now(clock).minus(STALE);
        tenantIterator.forEachActiveTenant(tenantId -> {
            List<SupplierInvoiceHold> stale = transaction.execute(
                    _ -> holds.findByReleasedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(cutoff));
            if (stale != null) {
                stale.forEach(hold -> warn(tenantId, hold));
            }
        });
    }

    private void warn(@NonNull UUID tenantId, @NonNull SupplierInvoiceHold hold) {
        log.warn(
                "Supplier invoice held over 24 hours tenant={} eventId={} reason={} since={}",
                tenantId,
                hold.getEventId(),
                hold.getReason(),
                hold.getReceivedAt());
    }
}
