package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.entity.AuditTrailEntry;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.CancellationType;
import com.positivity.accounting.internal.enums.RefundMethod;
import com.positivity.accounting.internal.enums.RefundType;
import com.positivity.accounting.internal.service.AuditTrailQueryServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for AuditTrailQueryServiceImpl.
 *
 * Tests each query method and the mapToResponse private mapping.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditTrailQueryService Unit Tests")
class AuditTrailQueryServiceTest {

    @Mock
    private AuditTrailEntryRepository auditRepository;

    @InjectMocks
    private AuditTrailQueryServiceImpl service;

    private UUID testOrderId;
    private UUID testInvoiceId;
    private UUID testAuditId;
    private AuditTrailEntry testEntry;

    @BeforeEach
    void setUp() {
        testOrderId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testInvoiceId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        testAuditId = UUID.fromString("00000000-0000-0000-0000-000000000003");

        testEntry = AuditTrailEntry.builder()
                .auditId(testAuditId)
                .exceptionType(ExceptionType.PRICE_OVERRIDE)
                .actorId("user123")
                .actorRole("STORE_MANAGER")
                .timestamp(Instant.parse("2024-01-01T10:00:00Z"))
                .reason("Customer loyalty discount")
                .authorizationLevel("MANAGER")
                .policyVersion("1.0")
                .orderId(testOrderId)
                .lineItemId(UUID.fromString("00000000-0000-0000-0000-000000000004"))
                .originalPrice(new BigDecimal("100.00"))
                .adjustedPrice(new BigDecimal("80.00"))
                .overrideAmountOrPercent("20.00")
                .invoiceId(testInvoiceId)
                .accountingIntent(AccountingIntent.REVENUE_ADJUSTMENT)
                .accountingStatus(AccountingStatus.PENDING_POSTING)
                .build();
    }

    @Test
    @DisplayName("getByOrderId - returns mapped responses for order")
    void getByOrderId_returnsMappedResponses() {
        when(auditRepository.findByOrderIdOrderByTimestampAsc(testOrderId)).thenReturn(List.of(testEntry));

        List<AuditTrailResponse> results = service.getByOrderId(testOrderId);

        assertThat(results).hasSize(1);
        AuditTrailResponse r = results.get(0);
        assertThat(r.getAuditId()).isEqualTo(testAuditId);
        assertThat(r.getExceptionType()).isEqualTo(ExceptionType.PRICE_OVERRIDE);
        assertThat(r.getActorId()).isEqualTo("user123");
        assertThat(r.getActorRole()).isEqualTo("STORE_MANAGER");
        assertThat(r.getOrderId()).isEqualTo(testOrderId);
        assertThat(r.getOriginalPrice()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(r.getAdjustedPrice()).isEqualByComparingTo(new BigDecimal("80.00"));
        verify(auditRepository).findByOrderIdOrderByTimestampAsc(testOrderId);
    }

    @Test
    @DisplayName("getByOrderId - returns empty list when no entries found")
    void getByOrderId_returnsEmptyList() {
        when(auditRepository.findByOrderIdOrderByTimestampAsc(testOrderId)).thenReturn(List.of());

        List<AuditTrailResponse> results = service.getByOrderId(testOrderId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("getByInvoiceId - returns mapped responses for invoice")
    void getByInvoiceId_returnsMappedResponses() {
        when(auditRepository.findByInvoiceIdOrderByTimestampAsc(testInvoiceId)).thenReturn(List.of(testEntry));

        List<AuditTrailResponse> results = service.getByInvoiceId(testInvoiceId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getInvoiceId()).isEqualTo(testInvoiceId);
        verify(auditRepository).findByInvoiceIdOrderByTimestampAsc(testInvoiceId);
    }

    @Test
    @DisplayName("getByTypeAndDateRange - returns filtered entries")
    void getByTypeAndDateRange_returnsFilteredEntries() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-31T23:59:59Z");

        when(auditRepository.findByExceptionTypeAndDateRange(eq(ExceptionType.PRICE_OVERRIDE), eq(start), eq(end)))
                .thenReturn(List.of(testEntry));

        List<AuditTrailResponse> results = service.getByTypeAndDateRange(ExceptionType.PRICE_OVERRIDE, start, end);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getExceptionType()).isEqualTo(ExceptionType.PRICE_OVERRIDE);
        verify(auditRepository).findByExceptionTypeAndDateRange(ExceptionType.PRICE_OVERRIDE, start, end);
    }

    @Test
    @DisplayName("getByActorAndDateRange - returns filtered entries")
    void getByActorAndDateRange_returnsFilteredEntries() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-31T23:59:59Z");

        when(auditRepository.findByActorAndDateRange(eq("user123"), eq(start), eq(end)))
                .thenReturn(List.of(testEntry));

        List<AuditTrailResponse> results = service.getByActorAndDateRange("user123", start, end);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getActorId()).isEqualTo("user123");
        verify(auditRepository).findByActorAndDateRange("user123", start, end);
    }

    @Test
    @DisplayName("getByDateRange - returns all entries in range")
    void getByDateRange_returnsEntriesInRange() {
        Instant start = Instant.parse("2024-01-01T00:00:00Z");
        Instant end = Instant.parse("2024-01-31T23:59:59Z");

        when(auditRepository.findByDateRange(eq(start), eq(end))).thenReturn(List.of(testEntry));

        List<AuditTrailResponse> results = service.getByDateRange(start, end);

        assertThat(results).hasSize(1);
        verify(auditRepository).findByDateRange(start, end);
    }

    @Test
    @DisplayName("mapToResponse - maps all fields including optional ones")
    void mapToResponse_mapsAllFields() {
        UUID paymentId = UUID.fromString("00000000-0000-0000-0000-000000000005");
        UUID sourceEventId = UUID.fromString("00000000-0000-0000-0000-000000000006");
        String sourceDocumentId = "DOC-0007";

        AuditTrailEntry fullEntry = AuditTrailEntry.builder()
                .auditId(testAuditId)
                .exceptionType(ExceptionType.REFUND)
                .actorId("manager1")
                .actorRole("MANAGER")
                .timestamp(Instant.parse("2024-06-01T12:00:00Z"))
                .reason("Customer complaint")
                .authorizationLevel("MANAGER")
                .policyVersion("2.0")
                .orderId(testOrderId)
                .invoiceId(testInvoiceId)
                .paymentId(paymentId)
                .refundType(RefundType.REVERSAL)
                .refundAmount(new BigDecimal("150.00"))
                .refundMethod(RefundMethod.CASH_REFUND)
                .cancellationType(CancellationType.ORDER_CANCELLED)
                .accountingIntent(AccountingIntent.PAYMENT_REVERSAL)
                .accountingStatus(AccountingStatus.POSTED)
                .sourceEventId(sourceEventId)
                .sourceDocumentId(sourceDocumentId)
                .build();

        when(auditRepository.findByOrderIdOrderByTimestampAsc(testOrderId)).thenReturn(List.of(fullEntry));

        List<AuditTrailResponse> results = service.getByOrderId(testOrderId);

        assertThat(results).hasSize(1);
        AuditTrailResponse r = results.get(0);
        assertThat(r.getPaymentId()).isEqualTo(paymentId);
        assertThat(r.getRefundType()).isEqualTo(RefundType.REVERSAL);
        assertThat(r.getRefundAmount()).isEqualByComparingTo(new BigDecimal("150.00"));
        assertThat(r.getRefundMethod()).isEqualTo(RefundMethod.CASH_REFUND);
        assertThat(r.getCancellationType()).isEqualTo(CancellationType.ORDER_CANCELLED);
        assertThat(r.getAccountingIntent()).isEqualTo(AccountingIntent.PAYMENT_REVERSAL);
        assertThat(r.getAccountingStatus()).isEqualTo(AccountingStatus.POSTED);
        assertThat(r.getSourceEventId()).isEqualTo(sourceEventId);
        assertThat(r.getSourceDocumentId()).isEqualTo(sourceDocumentId);
    }
}
