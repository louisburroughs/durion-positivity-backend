package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * The bill-intake meters (CAP:550 S25, #2518): {@code accounting.bill_intake.items{status,channel}} (drafts, across all
 * tenants, as of the last stats refresh), {@code accounting.bill_intake.scan{verdict}} (scans, counted as they run) and
 * {@code accounting.bill_intake.quarantine_age_seconds} (the age of the oldest quarantined file, the outage alert's
 * gauge). Tags carry no tenant, file or vendor: ADR-0072.
 */
@Component
public class BillIntakeMetrics {

    static final String ITEMS_GAUGE = "accounting.bill_intake.items";
    static final String SCAN_COUNTER = "accounting.bill_intake.scan";
    static final String QUARANTINE_AGE_GAUGE = "accounting.bill_intake.quarantine_age_seconds";

    private final @Nullable MeterRegistry registry;
    private final Map<String, AtomicLong> items = new ConcurrentHashMap<>();
    private final AtomicLong quarantineAgeSeconds = new AtomicLong();

    public BillIntakeMetrics(ObjectProvider<MeterRegistry> meterRegistry) {
        this.registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            for (BillIntakeStatus status : BillIntakeStatus.values()) {
                for (BillIntakeChannel channel : BillIntakeChannel.values()) {
                    AtomicLong value = new AtomicLong();
                    items.put(key(status, channel), value);
                    Gauge.builder(ITEMS_GAUGE, value, AtomicLong::get)
                            .description("Bill drafts by status and channel, across all tenants, as of the last refresh")
                            .tag("status", status.name())
                            .tag("channel", channel.name())
                            .register(registry);
                }
            }
            Gauge.builder(QUARANTINE_AGE_GAUGE, quarantineAgeSeconds, AtomicLong::get)
                    .description("Seconds the oldest quarantined bill file has waited, across all tenants")
                    .register(registry);
        }
    }

    /** One {@code accounting.bill_intake.scan} increment. */
    public void scan(MalwareScanner.@NonNull ScanVerdict verdict) {
        if (registry != null) {
            Counter.builder(SCAN_COUNTER)
                    .description("Malware scans of uploaded bill files, by verdict")
                    .tag("verdict", verdict.name().toLowerCase(Locale.ROOT))
                    .register(registry)
                    .increment();
        }
    }

    /** Publishes the fleet totals of one refresh. */
    public void publish(@NonNull Map<String, Long> counts, long oldestQuarantineSeconds) {
        items.forEach((key, value) -> value.set(counts.getOrDefault(key, 0L)));
        quarantineAgeSeconds.set(oldestQuarantineSeconds);
    }

    static @NonNull String key(@NonNull BillIntakeStatus status, @NonNull BillIntakeChannel channel) {
        return status.name() + "|" + channel.name();
    }
}
