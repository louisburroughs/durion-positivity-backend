package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.audit.entity.RefundPolicyConfig;
import com.positivity.accounting.internal.audit.repository.RefundPolicyConfigRepository;
import com.positivity.accounting.internal.enums.RefundMethod;
import com.positivity.accounting.internal.enums.RefundPaymentStatus;
import com.positivity.accounting.internal.enums.RefundType;
import com.positivity.accounting.internal.service.RefundAuthorizationServiceImpl;
import com.positivity.accounting.internal.service.RefundAuthorizationServiceImpl.RefundAuthorizationResult;

/**
 * Unit tests for RefundAuthorizationService
 * 
 * Tests refund authorization validation and refund method determination
 * based on payment status and refund type.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RefundAuthorizationService Unit Tests")
class RefundAuthorizationServiceTest {

    @Mock
    private RefundPolicyConfigRepository policyRepository;

    @InjectMocks
    private RefundAuthorizationServiceImpl service;

    private String testActorRole;
    private RefundPolicyConfig testPolicy;

    @BeforeEach
    void setUp() {
        testActorRole = "STORE_MANAGER";

        testPolicy = new RefundPolicyConfig();
        testPolicy.setVersion("v1.0");
        testPolicy.setRequiresSeparateAuthorization(false);
    }

    // ===== AUTHORIZATION TESTS =====

    @Test
    @DisplayName("validate - authorizes refund with active policy")
    void validate_withActivePolicy_authorized() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getPolicyVersion()).isEqualTo("v1.0");
        assertThat(result.getRefundMethod()).isNotNull();
    }

    @Test
    @DisplayName("validate - rejects when no active policy found")
    void validate_noActivePolicy_rejected() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.empty());

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.isAuthorized()).isFalse();
        assertThat(result.getMessage()).contains("No active refund policy");
    }

    @Test
    @DisplayName("validate - handles separate authorization requirement")
    void validate_separateAuthorizationRequired_authorized() {
        // Arrange
        testPolicy.setRequiresSeparateAuthorization(true);
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getPolicyVersion()).isEqualTo("v1.0");
    }

    // ===== REFUND METHOD DETERMINATION - SETTLED PAYMENTS =====

    @Test
    @DisplayName("validate - determines CREDIT_MEMO for settled credit memo refund")
    void validate_settledCreditMemo_returnsCreditMemoMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CREDIT_MEMO);
    }

    @Test
    @DisplayName("validate - determines CASH_REFUND for settled non-credit-memo refund")
    void validate_settledCashRefund_returnsCashRefundMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.REVERSAL // Not credit memo
        );

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CASH_REFUND);
    }

    // ===== REFUND METHOD DETERMINATION - UNSETTLED PAYMENTS =====

    @Test
    @DisplayName("validate - determines VOID for unsettled reversal")
    void validate_unsettledReversal_returnsVoidMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.PENDING,
                RefundType.REVERSAL);

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.VOID);
    }

    @Test
    @DisplayName("validate - determines CHARGEBACK for unsettled non-reversal")
    void validate_unsettledChargeback_returnsChargebackMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.AUTHORIZED,
                RefundType.CREDIT_MEMO // Not reversal
        );

        // Assert
        assertThat(result.isAuthorized()).isTrue();
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CHARGEBACK);
    }

    // ===== COMPREHENSIVE METHOD DETERMINATION TESTS =====

    @Test
    @DisplayName("validate - settled + credit_memo -> CREDIT_MEMO")
    void validate_settledCreditMemoType_correctMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CREDIT_MEMO);
    }

    @Test
    @DisplayName("validate - settled + reversal -> CASH_REFUND")
    void validate_settledReversalType_correctMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.SETTLED,
                RefundType.REVERSAL);

        // Assert
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CASH_REFUND);
    }

    @Test
    @DisplayName("validate - authorized + reversal -> VOID")
    void validate_authorizedReversalType_correctMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.AUTHORIZED,
                RefundType.REVERSAL);

        // Assert
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.VOID);
    }

    @Test
    @DisplayName("validate - pending + credit_memo -> CHARGEBACK")
    void validate_pendingCreditMemoType_correctMethod() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult result = service.validate(
                testActorRole,
                RefundPaymentStatus.PENDING,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(result.getRefundMethod()).isEqualTo(RefundMethod.CHARGEBACK);
    }

    // ===== DIFFERENT ROLES =====

    @Test
    @DisplayName("validate - authorizes for different roles")
    void validate_differentRoles_authorized() {
        // Arrange
        when(policyRepository.findActivePolicy()).thenReturn(Optional.of(testPolicy));

        // Act
        RefundAuthorizationResult managerResult = service.validate(
                "STORE_MANAGER",
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        RefundAuthorizationResult cashierResult = service.validate(
                "CASHIER",
                RefundPaymentStatus.SETTLED,
                RefundType.CREDIT_MEMO);

        // Assert
        assertThat(managerResult.isAuthorized()).isTrue();
        assertThat(cashierResult.isAuthorized()).isTrue();
    }
}
