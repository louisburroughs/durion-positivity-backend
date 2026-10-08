package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.entity.SupplierInvoiceHold;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/** The held-invoice sweep (CAP:550 S24, #2517): release retries, the summed gauge, and the daily stale WARN. */
@DisplayName("SupplierInvoiceHoldSweep — releases, gauge and stale warnings (S24)")
class SupplierInvoiceHoldSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-08T06:15:00Z");
    private static final UUID TENANT_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e01");
    private static final UUID TENANT_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e02");
    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e03");

    private final SupplierInvoiceHoldRepository holds = mock();
    private final SupplierEventsListener listener = mock();
    private final TenantIterator tenants = mock();
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private SupplierInvoiceHoldSweep sweep;
    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void wire() {
        ObjectProvider<SupplierEventsListener> releaser = mock(ObjectProvider.class);
        when(releaser.getIfAvailable()).thenReturn(listener);
        ObjectProvider<MeterRegistry> registry = mock(ObjectProvider.class);
        when(registry.getIfAvailable()).thenReturn(meters);
        sweep = new SupplierInvoiceHoldSweep(
                holds,
                releaser,
                tenants,
                mock(PlatformTransactionManager.class),
                Clock.fixed(NOW, ZoneOffset.UTC),
                registry);
        // Two tenants, each visited in turn.
        when(tenants.forEachActiveTenant(any())).thenAnswer(inv -> {
            Consumer<UUID> work = inv.getArgument(0);
            work.accept(TENANT_A);
            work.accept(TENANT_B);
            return 2;
        });
        logger = (Logger) LoggerFactory.getLogger(SupplierInvoiceHoldSweep.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(logs);
    }

    private double gauge(SupplierInvoiceHold.Reason reason) {
        return meters.get("accounting.supplier_invoice.held")
                .tag("reason", reason.name())
                .gauge()
                .value();
    }

    @Test
    @DisplayName("retries the release of every copied vendor's holds, per tenant")
    void retriesReleases() {
        when(holds.findCopiedVendorIdsWithOpenHolds(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY))
                .thenReturn(List.of(VENDOR), List.of());

        sweep.sweep();

        verify(listener).releaseHolds(VENDOR);
    }

    @Test
    @DisplayName("the gauge is every tenant's open holds, summed, per reason")
    void gaugeSumsTenants() {
        when(holds.findCopiedVendorIdsWithOpenHolds(any())).thenReturn(List.of());
        when(holds.countByReasonAndReleasedAtIsNull(SupplierInvoiceHold.Reason.VENDOR_ID_MISSING))
                .thenReturn(2L, 3L);
        when(holds.countByReasonAndReleasedAtIsNull(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY))
                .thenReturn(1L, 0L);

        sweep.sweep();

        assertThat(gauge(SupplierInvoiceHold.Reason.VENDOR_ID_MISSING)).isEqualTo(5.0);
        assertThat(gauge(SupplierInvoiceHold.Reason.VENDOR_NOT_IN_COPY)).isEqualTo(1.0);
        verify(listener, never()).releaseHolds(any());
    }

    @Test
    @DisplayName("holds older than 24 hours are WARN-logged by event id, reason and age only")
    void warnsStaleHolds() {
        SupplierInvoiceHold stale = new SupplierInvoiceHold();
        stale.setEventId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4e04"));
        stale.setReason(SupplierInvoiceHold.Reason.VENDOR_ID_MISSING);
        stale.setReceivedAt(NOW.minus(Duration.ofHours(30)));
        stale.setPayload("{\"payload\":{\"vendorInvoiceNumber\":\"INV-SECRET\"}}");
        when(holds.findByReleasedAtIsNullAndReceivedAtBeforeOrderByReceivedAtAsc(NOW.minus(Duration.ofHours(24))))
                .thenReturn(List.of(stale), List.of());

        sweep.warnStale();

        assertThat(logs.list)
                .singleElement()
                .satisfies(event -> assertThat(event.getFormattedMessage())
                        .contains(TENANT_A.toString(), stale.getEventId().toString(), "VENDOR_ID_MISSING")
                        .doesNotContain("INV-SECRET"));
    }
}
