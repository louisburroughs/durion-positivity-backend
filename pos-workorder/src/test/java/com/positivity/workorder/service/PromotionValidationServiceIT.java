package com.positivity.workorder.service;

import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;

import static org.assertj.core.api.Assertions.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.workorder.internal.dto.PromotionValidationResult;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.exception.PromotionValidationException;
import com.positivity.workorder.internal.exception.PromotionValidationException.PromotionErrorCode;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;

/**
 * Integration tests for PromotionValidationService.
 * Tests all scenarios from CAP:004 Story #25 acceptance criteria.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PromotionValidationServiceIT {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

        @Autowired
        private PromotionValidationService validationService;

        @Autowired
        private EstimateRepository estimateRepository;

        @Autowired
        private EstimateItemRepository estimateItemRepository;

        @Autowired
        private WorkorderRepository workorderRepository;

        private UUID testCustomerId;
        private UUID testVehicleId;
        private UUID testLocationId;

        @BeforeEach
        void setUp() {
                testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
                testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }

        @Test
        @DisplayName("Scenario 1: Successful validation with valid preconditions")
        void testSuccessfulValidationWithValidPreconditions() {
                // Given an approved estimate with approved items
                Estimate estimate = createApprovedEstimate();
                createApprovedItem(estimate.getId(), "Oil change", BigDecimal.valueOf(50.00));

                // When validating promotion preconditions
                // Then no exception is thrown
                assertThatCode(() -> validationService.validatePromotionPreconditions(estimate.getId()))
                                .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("Scenario 2: Block promotion due to expired approval")
        void testBlockPromotionDueToExpiredApproval() {
                // Given an estimate with expired approval
                Estimate estimate = createApprovedEstimate();
                estimate.setExpiresAt(LocalDateTime.now(TEST_CLOCK).minusDays(1)); // Expired yesterday
                estimateRepository.save(estimate);
                createApprovedItem(estimate.getId(), "Brake service", BigDecimal.valueOf(200.00));

                // When attempting to validate promotion
                // Then validation fails with APPROVAL_EXPIRED
                assertThatThrownBy(() -> validationService.validatePromotionPreconditions(estimate.getId()))
                                .isInstanceOf(PromotionValidationException.class)
                                .hasMessageContaining("expired")
                                .extracting("errorCode")
                                .isEqualTo(PromotionErrorCode.APPROVAL_EXPIRED);
        }

        @Test
        @DisplayName("Scenario 3: Block promotion due to invalid approval status")
        void testBlockPromotionDueToInvalidStatus() {
                // Given an estimate in DRAFT status (not approved)
                Estimate draftEstimate = Estimate.builder()
                                .estimateNumber("EST-2024-TEST-003")
                                .customerId(testCustomerId)
                                .vehicleId(testVehicleId)
                                .locationId(testLocationId)
                                .status(EstimateStatus.DRAFT) // Not approved
                                .currencyUomId("USD")
                                .createdById("test-user")
                                .build();
                Estimate savedEstimate = estimateRepository.save(draftEstimate);
                createApprovedItem(savedEstimate.getId(), "Tire rotation", BigDecimal.valueOf(30.00));

                // When attempting to validate promotion
                // Then validation fails with APPROVAL_INVALID
                assertThatThrownBy(() -> validationService.validatePromotionPreconditions(savedEstimate.getId()))
                                .isInstanceOf(PromotionValidationException.class)
                                .hasMessageContaining("DRAFT")
                                .extracting("errorCode")
                                .isEqualTo(PromotionErrorCode.APPROVAL_INVALID);
        }

        @Test
        @DisplayName("Scenario 4: Idempotent handling of duplicate promotion request")
        void testIdempotentHandlingOfDuplicatePromotion() {
                // Given an estimate that has already been promoted
                Estimate estimate = createApprovedEstimate();
                createApprovedItem(estimate.getId(), "Diagnostic service", BigDecimal.valueOf(100.00));

                // Create existing workorder from this estimate
                Workorder workorder = Workorder.builder()
                                .estimate(estimate)
                                .customerId(estimate.getCustomerId())
                                .vehicleId(estimate.getVehicleId())
                                .shopId(estimate.getLocationId())
                                .status(WorkorderStatus.DRAFT)
                                .build();
                Workorder existingWorkorder = workorderRepository.save(workorder);
                UUID expectedWorkorderId = existingWorkorder.getId();

                // When attempting to promote the same estimate again
                // Then validation fails with ALREADY_PROMOTED and returns existing workorder
                // reference
                assertThatThrownBy(() -> validationService.validatePromotionPreconditions(estimate.getId()))
                                .isInstanceOf(PromotionValidationException.class)
                                .hasMessageContaining("already been promoted")
                                .satisfies(ex -> {
                                        PromotionValidationException pve = (PromotionValidationException) ex;
                                        assertThat(pve.getErrorCode()).isEqualTo(PromotionErrorCode.ALREADY_PROMOTED);
                                        assertThat(pve.getExistingWorkorderId()).isEqualTo(expectedWorkorderId);
                                });
        }

        @Test
        @DisplayName("Block promotion when estimate not found")
        void testBlockPromotionWhenEstimateNotFound() {
                // Given a non-existent estimate ID
                UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

                // When attempting to validate promotion
                // Then validation fails with ESTIMATE_NOT_FOUND
                assertThatThrownBy(() -> validationService.validatePromotionPreconditions(nonExistentId))
                                .isInstanceOf(PromotionValidationException.class)
                                .hasMessageContaining("not found")
                                .extracting("errorCode")
                                .isEqualTo(PromotionErrorCode.ESTIMATE_NOT_FOUND);
        }

        @Test
        @DisplayName("Block promotion when no approved items exist")
        void testBlockPromotionWhenNoApprovedItems() {
                // Given an approved estimate with no approved items
                Estimate estimate = createApprovedEstimate();

                // Create a pending item (not approved)
                EstimateItem pendingItem = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Pending service")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(BigDecimal.valueOf(75.00))
                                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                                .createdById("test-user")
                                .build();
                estimateItemRepository.save(pendingItem);

                // When attempting to validate promotion
                // Then validation fails with NO_APPROVED_ITEMS
                assertThatThrownBy(() -> validationService.validatePromotionPreconditions(estimate.getId()))
                                .isInstanceOf(PromotionValidationException.class)
                                .hasMessageContaining("no approved items")
                                .extracting("errorCode")
                                .isEqualTo(PromotionErrorCode.NO_APPROVED_ITEMS);
        }

        @Test
        @DisplayName("Non-throwing validation returns success result")
        void testNonThrowingValidationReturnsSuccess() {
                // Given a valid approved estimate
                Estimate estimate = createApprovedEstimate();
                createApprovedItem(estimate.getId(), "Battery replacement", BigDecimal.valueOf(150.00));

                // When using non-throwing validation
                PromotionValidationResult result = validationService
                                .validatePromotionPreconditionsNonThrowing(estimate.getId());

                // Then result indicates success
                assertThat(result.isValid()).isTrue();
                assertThat(result.getErrorCode()).isNull();
                assertThat(result.getErrorMessage()).isNull();
        }

        @Test
        @DisplayName("Non-throwing validation returns failure result with error details")
        void testNonThrowingValidationReturnsFailure() {
                // Given an estimate with expired approval
                Estimate estimate = createApprovedEstimate();
                estimate.setExpiresAt(LocalDateTime.now(TEST_CLOCK).minusDays(1));
                estimateRepository.save(estimate);
                createApprovedItem(estimate.getId(), "Air filter", BigDecimal.valueOf(25.00));

                // When using non-throwing validation
                PromotionValidationResult result = validationService
                                .validatePromotionPreconditionsNonThrowing(estimate.getId());

                // Then result indicates failure with error details
                assertThat(result.isValid()).isFalse();
                assertThat(result.getErrorCode()).isEqualTo("APPROVAL_EXPIRED");
                assertThat(result.getErrorMessage()).contains("expired");
        }

        @Test
        @DisplayName("Non-throwing validation returns duplicate result with workorder reference")
        void testNonThrowingValidationReturnsDuplicateResult() {
                // Given an already-promoted estimate
                Estimate estimate = createApprovedEstimate();
                createApprovedItem(estimate.getId(), "Cabin air filter", BigDecimal.valueOf(35.00));

                Workorder existingWorkorder = Workorder.builder()
                                .estimate(estimate)
                                .customerId(estimate.getCustomerId())
                                .vehicleId(estimate.getVehicleId())
                                .shopId(estimate.getLocationId())
                                .status(WorkorderStatus.DRAFT)
                                .build();
                existingWorkorder = workorderRepository.save(existingWorkorder);

                // When using non-throwing validation
                PromotionValidationResult result = validationService
                                .validatePromotionPreconditionsNonThrowing(estimate.getId());

                // Then result indicates duplicate with workorder reference
                assertThat(result.isValid()).isFalse();
                assertThat(result.getErrorCode()).isEqualTo("ALREADY_PROMOTED");
                assertThat(result.getExistingWorkorderId()).isEqualTo(existingWorkorder.getId());
        }

        // Helper methods

        private Estimate createApprovedEstimate() {
                Estimate estimate = Estimate.builder()
                                .estimateNumber("EST-2024-TEST-" + System.currentTimeMillis())
                                .customerId(testCustomerId)
                                .vehicleId(testVehicleId)
                                .locationId(testLocationId)
                                .status(EstimateStatus.APPROVED)
                                .approvedAt(LocalDateTime.now(TEST_CLOCK))
                                .approvedBy(testCustomerId)
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30)) // Valid for 30 days
                                .currencyUomId("USD")
                                .createdById("test-user")
                                .build();
                return estimateRepository.save(estimate);
        }

        private EstimateItem createApprovedItem(UUID estimateId, String description, BigDecimal price) {
                EstimateItem item = EstimateItem.builder()
                                .estimate(estimateRepository.getReferenceById(estimateId))
                                .itemType(EstimateItemType.LABOR)
                                .description(description)
                                .quantity(BigDecimal.ONE)
                                .unitPrice(price)
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK))
                                .createdById("test-user")
                                .build();
                return estimateItemRepository.save(item);
        }
}
