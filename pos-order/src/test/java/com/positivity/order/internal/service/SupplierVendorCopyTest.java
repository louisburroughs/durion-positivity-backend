package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import com.positivity.order.internal.repository.PurchaseOrderRepository;
import com.positivity.order.internal.repository.PurchaseOrderTransmissionEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * pos-order's vendor copy (CAP:550 S24, #2517; AC 1 for pos-order; ADR-0044 R3; Security ruling on #2617).
 *
 * <p>Driven through {@link SupplierOrderResultListener}, the module's one {@code supplier.events.v1} consumer, so the
 * mark, its owner and the failure handling are those the container sees. Every log line of {@code
 * com.positivity.order} is captured at DEBUG: a registration number or {@code last4} must never appear, and the
 * assertions compare against constants without printing a captured line.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SupplierOrderResultListener — the supplier.vendor.updated copy (S24, #2517)")
class SupplierVendorCopyTest {

    private static final UUID VENDOR_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f5a01");
    private static final Instant NOW = Instant.parse("2026-10-08T12:00:00Z");

    /** The fake full number a version 1 fact carried, and its unseparated form. */
    private static final String FULL_NUMBER = "000-00-1234";

    private static final String FULL_NUMBER_UNSEPARATED = "000001234";
    private static final String LAST4 = "Z9Q8";

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtSupplierVendorRepository vendorRepository;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger orderLogger = (Logger) LoggerFactory.getLogger("com.positivity.order");
    private Level previousLevel;
    private SupplierOrderResultListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        when(meters.getIfAvailable()).thenReturn(meterRegistry);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ObjectMapper objectMapper = new ObjectMapper();
        listener = new SupplierOrderResultListener(
                clock,
                objectMapper,
                processedEventRepository,
                mock(PurchaseOrderRepository.class),
                mock(PurchaseOrderTransmissionEventRepository.class),
                new SupplierVendorReplica(clock, objectMapper, vendorRepository, meters),
                mock(PlatformTransactionManager.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.empty());

        previousLevel = orderLogger.getLevel();
        orderLogger.setLevel(Level.DEBUG);
        logs.start();
        orderLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        orderLogger.detachAppender(logs);
        orderLogger.setLevel(previousLevel);
    }

    private static String vendorFact(
            String eventId, int schemaVersion, long aggregateVersion, String status, String registration) {
        return """
            {"eventId":"%s","eventType":"supplier.vendor.updated","schemaVersion":%d,"aggregateId":"%s",
             "aggregateVersion":%d,"payload":{
              "vendorId":"%s","vendorNumber":"V-000123","legalName":"Acme Parts Ltd","displayName":"Acme Parts",
              "taxRegistrations":[%s],"remitTo":null,"remitToVersion":0,"defaultPaymentTerms":"NET30",
              "defaultCurrency":"EUR","status":"%s","statusChangedAt":"2026-10-07T09:00:00Z","statusReason":null,
              "remitToChangedAt":null,"remitToRequestedBy":null,"remitToApprovedBy":null,"createdBy":"alice",
              "createdAt":"2026-10-01T00:00:00Z","occurredAt":"2026-10-07T09:00:00Z"}}
            """.formatted(eventId, schemaVersion, VENDOR_ID, aggregateVersion, VENDOR_ID, registration, status);
    }

    private static String v2Fact(String eventId, long aggregateVersion, String status) {
        return vendorFact(
                eventId,
                2,
                aggregateVersion,
                status,
                "{\"scheme\":\"VAT\",\"region\":null,\"last4\":\"" + LAST4 + "\"}");
    }

    private static String v1Fact(String eventId) {
        return vendorFact(
                eventId, 1, 3L, "ACTIVE", "{\"scheme\":\"SSN\",\"number\":\"" + FULL_NUMBER + "\",\"region\":null}");
    }

    private static ExtSupplierVendor held(long aggregateVersion) {
        return ExtSupplierVendor.builder()
                .vendorId(VENDOR_ID)
                .vendorNumber("V-000123")
                .displayName("Acme Parts (old name)")
                .status(ExtSupplierVendor.Status.ACTIVE)
                .aggregateVersion(aggregateVersion)
                .updatedAt(NOW.minusSeconds(3600))
                .build();
    }

    private ExtSupplierVendor saved() {
        ArgumentCaptor<ExtSupplierVendor> captor = ArgumentCaptor.forClass(ExtSupplierVendor.class);
        verify(vendorRepository).save(captor.capture());
        return captor.getValue();
    }

    private ProcessedEvent mark() {
        ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(captor.capture());
        return captor.getValue();
    }

    private boolean anyLogContains(String value) {
        return logs.list.stream()
                .anyMatch(event -> event.getFormattedMessage().contains(value)
                        || (event.getThrowableProxy() != null
                                && String.valueOf(event.getThrowableProxy().getMessage())
                                        .contains(value)));
    }

    @Test
    @DisplayName("a version 2 fact is copied with its status, and the mark carries owner supplier")
    void v2FactIsCopied() {
        listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000001", 4L, "ACTIVE"));

        ExtSupplierVendor row = saved();
        assertThat(row.getVendorId()).isEqualTo(VENDOR_ID);
        assertThat(row.getVendorNumber()).isEqualTo("V-000123");
        assertThat(row.getDisplayName()).isEqualTo("Acme Parts");
        assertThat(row.getStatus()).isEqualTo(ExtSupplierVendor.Status.ACTIVE);
        assertThat(row.getStatusChangedAt()).isEqualTo(Instant.parse("2026-10-07T09:00:00Z"));
        assertThat(row.getAggregateVersion()).isEqualTo(4L);
        assertThat(row.getUpdatedAt()).isEqualTo(NOW);
        assertThat(mark().getOwner()).isEqualTo("supplier");
        assertThat(anyLogContains(LAST4)).as("last4 is never logged").isFalse();
    }

    @Test
    @DisplayName("an inactive vendor is copied as INACTIVE: the copy holds every vendor")
    void inactiveVendorIsCopied() {
        listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000002", 4L, "INACTIVE"));

        assertThat(saved().getStatus()).isEqualTo(ExtSupplierVendor.Status.INACTIVE);
    }

    @Test
    @DisplayName("an older aggregateVersion changes nothing, and is still marked")
    void olderVersionChangesNothing() {
        when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(held(5L)));

        listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000003", 4L, "INACTIVE"));

        verify(vendorRepository, never()).save(any());
        assertThat(mark().getOwner()).isEqualTo("supplier");
    }

    @Test
    @DisplayName("an equal aggregateVersion re-applies, so a replay repairs a drifted copy")
    void equalVersionReapplies() {
        ExtSupplierVendor existing = held(5L);
        when(vendorRepository.findById(VENDOR_ID)).thenReturn(Optional.of(existing));

        listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000004", 5L, "INACTIVE"));

        ExtSupplierVendor row = saved();
        assertThat(row).isSameAs(existing);
        assertThat(row.getDisplayName()).isEqualTo("Acme Parts");
        assertThat(row.getStatus()).isEqualTo(ExtSupplierVendor.Status.INACTIVE);
    }

    @Test
    @DisplayName(
            "a version 1 fact is marked (owner supplier), counted by eventType and schemaVersion only, and skipped")
    void v1FactIsMarkedCountedAndSkipped() {
        listener.onSupplierEvent(v1Fact("018f0a1b-0000-7000-8000-000000000005"));

        verify(vendorRepository, never()).findById(any());
        verify(vendorRepository, never()).save(any());
        assertThat(mark().getOwner()).isEqualTo("supplier");
        Counter skipped = meterRegistry
                .find(SupplierVendorReplica.SKIPPED_METRIC)
                .tags("eventType", "supplier.vendor.updated", "schemaVersion", "1")
                .counter();
        assertThat(skipped).isNotNull();
        assertThat(skipped.count()).isEqualTo(1.0d);
        assertThat(skipped.getId().getTags())
                .extracting(Tag::getKey)
                .containsExactlyInAnyOrder("eventType", "schemaVersion");
        assertThat(anyLogContains(FULL_NUMBER))
                .as("the full number is never logged")
                .isFalse();
        assertThat(anyLogContains(FULL_NUMBER_UNSEPARATED))
                .as("nor its unseparated form")
                .isFalse();
    }

    @Test
    @DisplayName("a database failure of the copy propagates for retry and writes no mark")
    void transientDatabaseFailurePropagatesUnmarked() {
        when(vendorRepository.save(any())).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000006", 4L, "ACTIVE")))
                .isInstanceOf(DataAccessResourceFailureException.class);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("a non-transient database failure of the copy propagates too: a mark would lose the vendor")
    void nonTransientDatabaseFailurePropagatesUnmarked() {
        when(vendorRepository.save(any())).thenThrow(new DataIntegrityViolationException("value too long"));

        assertThatThrownBy(() -> listener.onSupplierEvent(v2Fact("018f0a1b-0000-7000-8000-000000000007", 4L, "ACTIVE")))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("an unreadable version 2 fact is marked, and its payload never reaches the log")
    void unreadableFactIsMarkedWithoutLoggingPayload() {
        String broken = v2Fact("018f0a1b-0000-7000-8000-000000000008", 4L, "ACTIVE")
                .replace("\"vendorId\":\"" + VENDOR_ID + "\",", "");

        listener.onSupplierEvent(broken);

        verify(vendorRepository, never()).save(any());
        assertThat(mark().getOwner()).isEqualTo("supplier");
        assertThat(anyLogContains(LAST4)).isFalse();
        assertThat(anyLogContains("Acme Parts Ltd")).isFalse();
        assertThat(logs.list).as("the skip is logged").isNotEmpty();
    }
}
