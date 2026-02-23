package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.positivity.accounting.internal.audit.entity.OverridePolicyThreshold;
import com.positivity.accounting.internal.audit.entity.PolicyValidationResult;
import com.positivity.accounting.internal.audit.repository.OverridePolicyThresholdRepository;
import com.positivity.accounting.internal.service.PriceOverrideAuthorizationServiceImpl.AuthorizationResult;

/**
 * Unit tests for PriceOverrideAuthorizationService
 * 
 * Tests price override authorization validation including
 * threshold checks and forbidden category detection.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PriceOverrideAuthorizationService Unit Tests")
class PriceOverrideAuthorizationServiceTest {

    @Mock
    private OverridePolicyThresholdRepository policyRepository;

    @InjectMocks
    private PriceOverrideAuthorizationService service;

    private String testRole;
    private BigDecimal originalPrice;
    private OverridePolicyThreshold testPolicy;

    @BeforeEach
    void setUp() {
        testRole = "STORE_MANAGER";
        originalPrice = new BigDecimal("100.00");

        testPolicy = new OverridePolicyThreshold();
        testPolicy.setRole(testRole);
        testPolicy.setVersion("v1.0");
        testPolicy.setMaxAbsoluteAmount(new BigDecimal("50.00"));
        testPolicy.setMaxPercentOff(new BigDecimal("25.0"));
    }

    // ===== APPROVAL TESTS =====

    @Test
    @DisplayName("validate - approves override within both thresholds")
    void validate_withinThresholds_approved() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00"); // 10% off, $10 discount
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isTrue();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.APPROVED);
        assertThat(result.getPolicyVersion()).isEqualTo("v1.0");
        assertThat(result.getAuthorizationLevel()).isEqualTo(testRole);
    }

    @Test
    @DisplayName("validate - approves override at exact absolute threshold")
    void validate_atAbsoluteThreshold_approved() {
        // Arrange
        // Original: $100, threshold: $50 absolute OR 25% off
        // To stay within both thresholds: adjust to $75 (25% off, $25 discount)
        BigDecimal adjustedPrice = new BigDecimal("75.00"); // Exactly 25% off = $25 discount
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isTrue();
    }

    @Test
    @DisplayName("validate - approves override at exact percentage threshold")
    void validate_atPercentageThreshold_approved() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("75.00"); // Exactly 25% off
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isTrue();
    }

    // ===== REJECTION TESTS - THRESHOLDS =====

    @Test
    @DisplayName("validate - rejects override exceeding absolute amount")
    void validate_exceedsAbsoluteAmount_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("45.00"); // $55 discount, exceeds $50 limit
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED);
        assertThat(result.getMessage()).contains("Discount amount exceeds authorized threshold");
        assertThat(result.getPolicyVersion()).isEqualTo("v1.0");
    }

    @Test
    @DisplayName("validate - rejects override exceeding percentage")
    void validate_exceedsPercentage_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("70.00"); // 30% off, exceeds 25% limit
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED);
        assertThat(result.getMessage()).contains("Discount percentage exceeds authorized threshold");
    }

    @Test
    @DisplayName("validate - rejects when no policy found for role")
    void validate_noPolicyFound_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.empty());

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_THRESHOLD_EXCEEDED);
        assertThat(result.getMessage()).contains("No authorization policy found");
    }

    // ===== FORBIDDEN CATEGORY TESTS =====

    @Test
    @DisplayName("validate - rejects BELOW_COST category")
    void validate_belowCost_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        String forbiddenCategory = "BELOW_COST";

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, forbiddenCategory);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_FORBIDDEN);
        assertThat(result.getForbiddenCategoryCode()).isEqualTo(forbiddenCategory);
        assertThat(result.getMessage()).contains("Override category not permitted");
    }

    @Test
    @DisplayName("validate - rejects STACKING_VIOLATION category")
    void validate_stackingViolation_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        String forbiddenCategory = "STACKING_VIOLATION";

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, forbiddenCategory);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_FORBIDDEN);
        assertThat(result.getForbiddenCategoryCode()).isEqualTo(forbiddenCategory);
    }

    @Test
    @DisplayName("validate - rejects REGULATED_FEE category")
    void validate_regulatedFee_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        String forbiddenCategory = "REGULATED_FEE";

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, forbiddenCategory);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_FORBIDDEN);
    }

    @Test
    @DisplayName("validate - rejects WARRANTY_OVERRIDE category")
    void validate_warrantyOverride_rejected() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        String forbiddenCategory = "WARRANTY_OVERRIDE";

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, forbiddenCategory);

        // Assert
        assertThat(result.isApproved()).isFalse();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.REJECTED_FORBIDDEN);
    }

    @Test
    @DisplayName("validate - approves allowed category")
    void validate_allowedCategory_approved() {
        // Arrange
        BigDecimal adjustedPrice = new BigDecimal("90.00");
        String allowedCategory = "CUSTOMER_LOYALTY";
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, allowedCategory);

        // Assert
        assertThat(result.isApproved()).isTrue();
        assertThat(result.getResult()).isEqualTo(PolicyValidationResult.APPROVED);
    }

    // ===== NULL THRESHOLD TESTS =====

    @Test
    @DisplayName("validate - handles null absolute amount threshold")
    void validate_nullAbsoluteThreshold_approved() {
        // Arrange
        testPolicy.setMaxAbsoluteAmount(null); // No absolute limit
        BigDecimal adjustedPrice = new BigDecimal("10.00"); // 90% off, but within percentage
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert - should only check percentage threshold
        assertThat(result.isApproved()).isFalse(); // 90% exceeds 25% limit
    }

    @Test
    @DisplayName("validate - handles null percentage threshold")
    void validate_nullPercentageThreshold_approved() {
        // Arrange
        testPolicy.setMaxPercentOff(null); // No percentage limit
        BigDecimal adjustedPrice = new BigDecimal("45.00"); // Within absolute limit
        when(policyRepository.findCurrentActiveByRole(testRole)).thenReturn(Optional.of(testPolicy));

        // Act
        AuthorizationResult result = service.validate(testRole, originalPrice, adjustedPrice, null);

        // Assert - should only check absolute threshold
        assertThat(result.isApproved()).isFalse(); // $55 exceeds $50 limit
    }
}
