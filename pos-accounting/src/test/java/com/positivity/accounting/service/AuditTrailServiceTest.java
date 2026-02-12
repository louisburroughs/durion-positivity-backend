package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import com.positivity.accounting.internal.audit.dto.AuditTrailResponse;
import com.positivity.accounting.internal.audit.dto.CancellationRequest;
import com.positivity.accounting.internal.audit.dto.PriceOverrideRequest;
import com.positivity.accounting.internal.audit.dto.RefundRequest;
import com.positivity.accounting.internal.audit.entity.AuditTrailEntry;
import com.positivity.accounting.internal.audit.entity.ExceptionType;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.audit.event.AuthorizationDenied;
import com.positivity.accounting.internal.audit.event.CancellationCreated;
import com.positivity.accounting.internal.audit.event.OverridePriceCreated;
import com.positivity.accounting.internal.audit.event.RefundCreated;
import com.positivity.accounting.internal.audit.repository.AuditTrailEntryRepository;
import com.positivity.accounting.internal.enums.AccountingIntent;
import com.positivity.accounting.internal.enums.AccountingStatus;
import com.positivity.accounting.internal.enums.CancellationType;
import com.positivity.accounting.internal.enums.RefundMethod;
import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
import com.positivity.accounting.service.AuditTrailService.AuthorizationException;
import com.positivity.accounting.service.PriceOverrideAuthorizationService.AuthorizationResult;
import com.positivity.accounting.service.RefundAuthorizationService.RefundAuthorizationResult;

import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for AuditTrailService
 * 
 * Tests audit trail recording for price overrides, refunds, and cancellations
 * including authorization validation and event publishing.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("AuditTrailService Unit Tests")
class AuditTrailServiceTest {

    @Mock
    private AuditTrailEntryRepository auditRepository;

    @Mock
    private PriceOverrideAuthorizationService overrideAuthService;

    @Mock
    private RefundAuthorizationService refundAuthService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuditTrailService service;

    private UUID testActorId;
    private UUID testOrderId;
    private UUID testInvoiceId;
    private UUID testPaymentId;
    private UUID testLineItemId;

    @BeforeEach
    void setUp() {
        testActorId = UUID.randomUUID();
        testOrderId = UUID.randomUUID();
        testInvoiceId = UUID.randomUUID();
        testPaymentId = UUID.randomUUID();
        testLineItemId = UUID.randomUUID();
    }

    // ===== PRICE OVERRIDE TESTS =====

    @Test
    @DisplayName("recordPriceOverride - records approved override successfully")
    void recordPriceOverride_approved_success() {
        // Arrange
        PriceOverrideRequest request = createPriceOverrideRequest();
        AuthorizationResult authResult = AuthorizationResult.builder()
                .result(PolicyValidationResult.APPROVED)
                .policyVersion("v1.0")
                .authorizationLevel("STORE_MANAGER")
                .build();
        
        when(overrideAuthService.validate(any(), any(), any(), any())).thenReturn(authResult);
        when(auditRepository.save(any(AuditTrailEntry.class))).thenAnswer(inv -> {
            AuditTrailEntry entry = inv.getArgument(0);
            entry.setAuditId(UUID.randomUUID());
            return entry;
        });

        // Act
        AuditTrailResponse response = service.recordPriceOverride(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getExceptionType()).isEqualTo(ExceptionType.PRICE_OVERRIDE);
        assertThat(response.getOrderId()).isEqualTo(testOrderId);
        verify(auditRepository).save(any(AuditTrailEntry.class));
        verify(eventPublisher).publishEvent(any(OverridePriceCreated.class));
    }

    @Test
    @DisplayName("recordPriceOverride - saves entry with correct accounting status")
    void recordPriceOverride_savesWithCorrectStatus() {
        // Arrange
        PriceOverrideRequest request = createPriceOverrideRequest();
        AuthorizationResult authResult = AuthorizationResult.builder()
                .result(PolicyValidationResult.APPROVED)
                .policyVersion("v1.0")
                .authorizationLevel("STORE_MANAGER")
                .build();
        
        when(overrideAuthService.validate(any(), any(), any(), any())).thenReturn(authResult);
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordPriceOverride(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.REVENUE_ADJUSTMENT);
        assertThat(savedEntry.getAccountingStatus()).isEqualTo(AccountingStatus.PENDING_POSTING);
        assertThat(savedEntry.getPolicyValidationResult()).isEqualTo(PolicyValidationResult.APPROVED);
    }

    @Test
    @DisplayName("recordPriceOverride - rejects unauthorized override")
    void recordPriceOverride_unauthorized_throwsException() {
        // Arrange
        PriceOverrideRequest request = createPriceOverrideRequest();
        AuthorizationResult authResult = AuthorizationResult.builder()
                .result(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED)
                .message("Exceeds threshold")
                .policyVersion("v1.0")
                .build();
        
        when(overrideAuthService.validate(any(), any(), any(), any())).thenReturn(authResult);

        // Act & Assert
        assertThatThrownBy(() -> service.recordPriceOverride(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Exceeds threshold");
        
        verify(eventPublisher).publishEvent(any(AuthorizationDenied.class));
    }

    // ===== REFUND TESTS =====

    @Test
    @DisplayName("recordRefund - records authorized refund successfully")
    void recordRefund_authorized_success() throws Exception {
        // Arrange
        RefundRequest request = createRefundRequest();
        RefundAuthorizationResult authResult = RefundAuthorizationResult.builder()
                .authorized(true)
                .refundMethod(RefundMethod.CREDIT_MEMO)
                .policyVersion("v1.0")
                .build();
        
        when(refundAuthService.validate(any(), any(), any())).thenReturn(authResult);
        when(objectMapper.writeValueAsString(any())).thenReturn("{\"invoiceId\":\"test\"}");
        when(auditRepository.save(any(AuditTrailEntry.class))).thenAnswer(inv -> {
            AuditTrailEntry entry = inv.getArgument(0);
            entry.setAuditId(UUID.randomUUID());
            return entry;
        });

        // Act
        AuditTrailResponse response = service.recordRefund(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getExceptionType()).isEqualTo(ExceptionType.REFUND);
        assertThat(response.getInvoiceId()).isEqualTo(testInvoiceId);
        verify(auditRepository).save(any(AuditTrailEntry.class));
        verify(eventPublisher).publishEvent(any(RefundCreated.class));
    }

    @Test
    @DisplayName("recordRefund - determines correct accounting intent for REVERSAL")
    void recordRefund_reversalType_correctIntent() throws Exception {
        // Arrange
        RefundRequest request = createRefundRequest();
        request.setRefundType(RefundType.REVERSAL);
        
        RefundAuthorizationResult authResult = RefundAuthorizationResult.builder()
                .authorized(true)
                .refundMethod(RefundMethod.VOID)
                .policyVersion("v1.0")
                .build();
        
        when(refundAuthService.validate(any(), any(), any())).thenReturn(authResult);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordRefund(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.PAYMENT_REVERSAL);
    }

    @Test
    @DisplayName("recordRefund - determines correct accounting intent for CREDIT_MEMO")
    void recordRefund_creditMemoType_correctIntent() throws Exception {
        // Arrange
        RefundRequest request = createRefundRequest();
        request.setRefundType(RefundType.CREDIT_MEMO);
        
        RefundAuthorizationResult authResult = RefundAuthorizationResult.builder()
                .authorized(true)
                .refundMethod(RefundMethod.CREDIT_MEMO)
                .policyVersion("v1.0")
                .build();
        
        when(refundAuthService.validate(any(), any(), any())).thenReturn(authResult);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordRefund(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.CUSTOMER_CREDIT);
    }

    @Test
    @DisplayName("recordRefund - determines correct accounting intent for ADJUSTMENT")
    void recordRefund_adjustmentType_correctIntent() throws Exception {
        // Arrange
        RefundRequest request = createRefundRequest();
        request.setRefundType(RefundType.ADJUSTMENT);
        
        RefundAuthorizationResult authResult = RefundAuthorizationResult.builder()
                .authorized(true)
                .refundMethod(RefundMethod.CASH_REFUND)
                .policyVersion("v1.0")
                .build();
        
        when(refundAuthService.validate(any(), any(), any())).thenReturn(authResult);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordRefund(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.WRITE_OFF);
    }

    @Test
    @DisplayName("recordRefund - rejects unauthorized refund")
    void recordRefund_unauthorized_throwsException() {
        // Arrange
        RefundRequest request = createRefundRequest();
        RefundAuthorizationResult authResult = RefundAuthorizationResult.builder()
                .authorized(false)
                .message("Not authorized")
                .policyVersion("v1.0")
                .build();
        
        when(refundAuthService.validate(any(), any(), any())).thenReturn(authResult);

        // Act & Assert
        assertThatThrownBy(() -> service.recordRefund(request))
                .isInstanceOf(AuthorizationException.class)
                .hasMessageContaining("Not authorized");
        
        verify(eventPublisher).publishEvent(any(AuthorizationDenied.class));
    }

    // ===== CANCELLATION TESTS =====

    @Test
    @DisplayName("recordCancellation - records ORDER_CANCELLED successfully")
    void recordCancellation_orderCancelled_success() {
        // Arrange
        CancellationRequest request = createCancellationRequest();
        request.setCancellationType(CancellationType.ORDER_CANCELLED);
        
        when(auditRepository.save(any(AuditTrailEntry.class))).thenAnswer(inv -> {
            AuditTrailEntry entry = inv.getArgument(0);
            entry.setAuditId(UUID.randomUUID());
            return entry;
        });

        // Act
        AuditTrailResponse response = service.recordCancellation(request);

        // Assert
        assertThat(response).isNotNull();
        assertThat(response.getExceptionType()).isEqualTo(ExceptionType.CANCELLATION);
        assertThat(response.getOrderId()).isEqualTo(testOrderId);
        verify(auditRepository).save(any(AuditTrailEntry.class));
        verify(eventPublisher).publishEvent(any(CancellationCreated.class));
    }

    @Test
    @DisplayName("recordCancellation - determines REVENUE_REVERSAL for ORDER_CANCELLED")
    void recordCancellation_orderCancelled_correctIntent() {
        // Arrange
        CancellationRequest request = createCancellationRequest();
        request.setCancellationType(CancellationType.ORDER_CANCELLED);
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordCancellation(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.REVENUE_REVERSAL);
    }

    @Test
    @DisplayName("recordCancellation - determines PAYMENT_RECOVERY for PAYMENT_FAILED")
    void recordCancellation_paymentFailed_correctIntent() {
        // Arrange
        CancellationRequest request = createCancellationRequest();
        request.setCancellationType(CancellationType.PAYMENT_FAILED);
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordCancellation(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getAccountingIntent()).isEqualTo(AccountingIntent.PAYMENT_RECOVERY);
    }

    @Test
    @DisplayName("recordCancellation - sets PENDING_REVERSAL status")
    void recordCancellation_setsCorrectStatus() {
        // Arrange
        CancellationRequest request = createCancellationRequest();
        
        ArgumentCaptor<AuditTrailEntry> entryCaptor = ArgumentCaptor.forClass(AuditTrailEntry.class);
        when(auditRepository.save(entryCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

        // Act
        service.recordCancellation(request);

        // Assert
        AuditTrailEntry savedEntry = entryCaptor.getValue();
        assertThat(savedEntry.getGlReversalStatus()).isEqualTo("PENDING_REVERSAL");
        assertThat(savedEntry.getAccountingStatus()).isEqualTo(AccountingStatus.PENDING_POSTING);
    }

    // ===== HELPER METHODS =====

    private PriceOverrideRequest createPriceOverrideRequest() {
        PriceOverrideRequest request = new PriceOverrideRequest();
        request.setActorId(testActorId);
        request.setActorRole("STORE_MANAGER");
        request.setOrderId(testOrderId);
        request.setLineItemId(testLineItemId);
        request.setOriginalPrice(new BigDecimal("100.00"));
        request.setAdjustedPrice(new BigDecimal("80.00"));
        request.setReason("Customer loyalty discount");
        return request;
    }

    private RefundRequest createRefundRequest() {
        RefundRequest request = new RefundRequest();
        request.setActorId(testActorId);
        request.setActorRole("STORE_MANAGER");
        request.setInvoiceId(testInvoiceId);
        request.setPaymentId(testPaymentId);
        request.setRefundType(RefundType.CREDIT_MEMO);
        request.setRefundAmount(new BigDecimal("50.00"));
        request.setOriginalPaymentStatus(RefundPaymentStatus.SETTLED);
        request.setReason("Product returned");
        return request;
    }

    private CancellationRequest createCancellationRequest() {
        CancellationRequest request = new CancellationRequest();
        request.setActorId(testActorId);
        request.setActorRole("STORE_MANAGER");
        request.setOrderId(testOrderId);
        request.setInvoiceId(testInvoiceId);
        request.setCancellationType(CancellationType.ORDER_CANCELLED);
        request.setReason("Customer cancelled order");
        request.setBeforeSnapshot("{}");
        request.setAfterSnapshot("{}");
        return request;
    }
}
