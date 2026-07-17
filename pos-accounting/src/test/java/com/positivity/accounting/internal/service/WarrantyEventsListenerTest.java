package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.WarrantyReimbursementExpectation;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.WarrantyReimbursementExpectationRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

class WarrantyEventsListenerTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-16T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID CLAIM_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID REIMBURSEMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID PROVIDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID AP_VENDOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final WarrantyReimbursementExpectationRepository expectations =
            mock(WarrantyReimbursementExpectationRepository.class);

    private WarrantyEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new WarrantyEventsListener(TEST_CLOCK, new ObjectMapper(), processedEvents, expectations);
    }

    private String submitted(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"warranty.reimbursement.submitted","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"claimId":"%s","claimCode":"WC-2026-000123","reimbursementId":"%s",
                            "providerId":"%s","apVendorId":"%s","amountRequested":250.00,
                            "vendorClaimReference":"PRV-77","submittedAt":"2026-07-15T09:00:00Z"}}
                """.formatted(eventId, CLAIM_ID, version, CLAIM_ID, REIMBURSEMENT_ID, PROVIDER_ID, AP_VENDOR_ID);
    }

    private String resolved(String eventId, long version, String status) {
        return """
                {"eventId":"%s","eventType":"warranty.reimbursement.resolved","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"claimId":"%s","claimCode":"WC-2026-000123","reimbursementId":"%s",
                            "providerId":"%s","apVendorId":"%s","status":"%s","amountApproved":200.00,
                            "creditReference":"CR-42","resolvedAt":"2026-07-16T10:00:00Z"}}
                """.formatted(eventId, CLAIM_ID, version, CLAIM_ID, REIMBURSEMENT_ID, PROVIDER_ID, AP_VENDOR_ID, status);
    }

    private WarrantyReimbursementExpectation expectedRow(long version) {
        return WarrantyReimbursementExpectation.builder()
                .reimbursementId(REIMBURSEMENT_ID)
                .claimId(CLAIM_ID)
                .claimCode("WC-2026-000123")
                .providerId(PROVIDER_ID)
                .apVendorId(AP_VENDOR_ID)
                .amountRequested(new BigDecimal("250.00"))
                .status(WarrantyReimbursementExpectation.STATUS_EXPECTED)
                .submittedAt(Instant.parse("2026-07-15T09:00:00Z"))
                .aggregateVersion(version)
                .updatedAt(Instant.now(TEST_CLOCK))
                .build();
    }

    @Test
    @DisplayName("Submitted fact creates an EXPECTED credit row and records the eventId")
    void submittedCreatesExpectation() {
        when(processedEvents.existsById("e-1")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenReturn(Optional.empty());

        listener.onWarrantyEvent(submitted("e-1", 3));

        ArgumentCaptor<WarrantyReimbursementExpectation> saved =
                ArgumentCaptor.forClass(WarrantyReimbursementExpectation.class);
        verify(expectations).save(saved.capture());
        assertThat(saved.getValue().getReimbursementId()).isEqualTo(REIMBURSEMENT_ID);
        assertThat(saved.getValue().getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("EXPECTED");
        assertThat(saved.getValue().getApVendorId()).isEqualTo(AP_VENDOR_ID);
        assertThat(saved.getValue().getAmountRequested()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(saved.getValue().getVendorClaimReference()).isEqualTo("PRV-77");
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(3L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Resolved fact updates the expectation with status, approved amount and credit reference")
    void resolvedUpdatesExpectation() {
        when(processedEvents.existsById("e-2")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenReturn(Optional.of(expectedRow(3)));

        listener.onWarrantyEvent(resolved("e-2", 5, "APPROVED"));

        ArgumentCaptor<WarrantyReimbursementExpectation> saved =
                ArgumentCaptor.forClass(WarrantyReimbursementExpectation.class);
        verify(expectations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(saved.getValue().getAmountApproved()).isEqualByComparingTo(new BigDecimal("200.00"));
        assertThat(saved.getValue().getCreditReference()).isEqualTo("CR-42");
        assertThat(saved.getValue().getResolvedAt()).isEqualTo(Instant.parse("2026-07-16T10:00:00Z"));
        // Data from the submitted fact is preserved.
        assertThat(saved.getValue().getAmountRequested()).isEqualByComparingTo(new BigDecimal("250.00"));
        assertThat(saved.getValue().getSubmittedAt()).isEqualTo(Instant.parse("2026-07-15T09:00:00Z"));
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(5L);
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Resolved fact without a prior row creates one from the resolution data")
    void resolvedWithoutSubmittedCreatesRow() {
        when(processedEvents.existsById("e-3")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenReturn(Optional.empty());

        listener.onWarrantyEvent(resolved("e-3", 5, "APPROVED"));

        ArgumentCaptor<WarrantyReimbursementExpectation> saved =
                ArgumentCaptor.forClass(WarrantyReimbursementExpectation.class);
        verify(expectations).save(saved.capture());
        assertThat(saved.getValue().getReimbursementId()).isEqualTo(REIMBURSEMENT_ID);
        assertThat(saved.getValue().getClaimId()).isEqualTo(CLAIM_ID);
        assertThat(saved.getValue().getStatus()).isEqualTo("APPROVED");
        assertThat(saved.getValue().getAmountRequested()).isNull();
        assertThat(saved.getValue().getSubmittedAt()).isNull();
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("WRITTEN_OFF resolution is stored so the credit stops being expected")
    void writtenOffStored() {
        when(processedEvents.existsById("e-4")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenReturn(Optional.of(expectedRow(3)));

        listener.onWarrantyEvent(resolved("e-4", 6, "WRITTEN_OFF"));

        ArgumentCaptor<WarrantyReimbursementExpectation> saved =
                ArgumentCaptor.forClass(WarrantyReimbursementExpectation.class);
        verify(expectations).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("WRITTEN_OFF");
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Skips duplicate events by eventId")
    void skipsDuplicates() {
        when(processedEvents.existsById("e-dup")).thenReturn(true);

        listener.onWarrantyEvent(submitted("e-dup", 3));

        verify(expectations, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Skips stale events whose aggregateVersion is at or below the row's")
    void skipsStaleVersions() {
        when(processedEvents.existsById("e-old")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenReturn(Optional.of(expectedRow(9)));

        listener.onWarrantyEvent(resolved("e-old", 5, "APPROVED"));

        verify(expectations, never()).save(any());
        // Still recorded as processed so redelivery does not reprocess it.
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Other warranty event types are recorded as processed and skipped")
    void recordsAndSkipsOtherEventTypes() {
        when(processedEvents.existsById("e-5")).thenReturn(false);

        listener.onWarrantyEvent("""
                {"eventId":"e-5","eventType":"warranty.claim.settled","aggregateVersion":4,"payload":{}}
                """);

        verify(expectations, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("e-6")).thenReturn(false);
        when(expectations.findById(REIMBURSEMENT_ID)).thenThrow(new QueryTimeoutException("db timeout"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onWarrantyEvent(submitted("e-6", 1)));

        verify(processedEvents, never()).save(any());
    }
}
