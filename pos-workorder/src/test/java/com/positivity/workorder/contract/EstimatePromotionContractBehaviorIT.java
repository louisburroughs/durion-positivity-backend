package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Contract behavior integration tests for estimate promotion to workorder.
 * CAP:004 Story #26 - Create Workorder from Approved Estimate
 *
 * Tests cover:
 * - Successful promotion of approved estimate to workorder
 * - Idempotency: duplicate promotion attempts return existing workorder
 * - Validation failures: estimate not found, not approved, expired, no approved
 * items
 * - Authorization checks
 */
@DisplayName("Estimate Promotion Contract Behavior Tests (CAP:004 Story #26)")
@Import(ContractTestConfiguration.class)
class EstimatePromotionContractBehaviorIT extends BaseContractIntegrationTest {
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    // ========== PROMOTION SUCCESS TESTS ==========

    @Test
    @DisplayName("PR-001: Successfully promote approved estimate to workorder")
    void testPromoteEstimate_HappyPath() {
        UUID estimateId = seedApprovedEstimateWithItems();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("estimateId", equalTo(estimateId.toString()))
                .body("customerId", equalTo(testCustomerId.toString()))
                .body("status", equalTo("DRAFT"))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-002: Idempotent promotion with Idempotency-Key returns same workorder")
    void testPromoteEstimate_IdempotencyWithHeader() {
        UUID estimateId = seedApprovedEstimateWithItems();
        String idempotencyKey = "test-key-" + UUID.fromString("00000000-0000-0000-0000-000000000001");

        // First promotion with idempotency key
        String workorderId = givenWithGatewayAuth()
                .header("Idempotency-Key", idempotencyKey)
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // Second promotion attempt with same idempotency key - should return same
        // workorder
        givenWithGatewayAuth()
                .header("Idempotency-Key", idempotencyKey)
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(workorderId))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-003: Promotion without Idempotency-Key uses business logic idempotency")
    void testPromoteEstimate_Idempotency() {
        UUID estimateId = seedApprovedEstimateWithItems();

        // First promotion
        String workorderId = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // Second promotion attempt - should return same workorder (business logic
        // idempotency)
        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(workorderId))
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-004: Different Idempotency-Keys for same estimate return same workorder")
    void testPromoteEstimate_DifferentKeys_SameEstimate() {
        UUID estimateId = seedApprovedEstimateWithItems();

        // First promotion with first idempotency key
        String workorderId = givenWithGatewayAuth()
                .header("Idempotency-Key", "test-key-1-" + UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        // Second promotion with different idempotency key but same estimate
        // Should return same workorder (business logic idempotency takes precedence)
        givenWithGatewayAuth()
                .header("Idempotency-Key", "test-key-2-" + UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(workorderId))
                .log()
                .ifValidationFails();
    }

    // ========== PROMOTION VALIDATION FAILURE TESTS ==========

    @Test
    @DisplayName("PR-005: Reject promotion - estimate not found")
    void testPromoteEstimate_NotFound() {
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", nonExistentId)
                .then()
                .statusCode(400)
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-006: Reject promotion - estimate not in APPROVED status")
    void testPromoteEstimate_NotApproved() {
        UUID estimateId = seedDraftEstimateWithItems();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - APPROVAL_INVALID
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-007: Reject promotion - estimate approval expired")
    void testPromoteEstimate_Expired() {
        UUID estimateId = seedExpiredApprovedEstimate();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - APPROVAL_EXPIRED
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("PR-008: Reject promotion - estimate has no approved items")
    void testPromoteEstimate_NoApprovedItems() {
        UUID estimateId = seedApprovedEstimateWithoutApprovedItems();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - NO_APPROVED_ITEMS
                .log()
                .ifValidationFails();
    }

    // ========== TEST DATA SEEDING HELPERS ==========

    private UUID seedApprovedEstimateWithItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-"
                        + UUID.fromString("00000000-0000-0000-0000-000000000001")
                                .toString()
                                .substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now(TEST_CLOCK))
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30)) // Not expired
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add approved items
        EstimateItem laborItem = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("50.00"))
                .lineTotal(new BigDecimal("50.00"))
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(laborItem);

        EstimateItem partItem = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.PART)
                .description("Engine oil filter")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("50.00"))
                .lineTotal(new BigDecimal("50.00"))
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(partItem);

        return estimate.getId();
    }

    private UUID seedDraftEstimateWithItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-"
                        + UUID.fromString("00000000-0000-0000-0000-000000000001")
                                .toString()
                                .substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.DRAFT) // Not approved
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("100.00"))
                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        return estimate.getId();
    }

    private UUID seedExpiredApprovedEstimate() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-"
                        + UUID.fromString("00000000-0000-0000-0000-000000000001")
                                .toString()
                                .substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusDays(40))
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now(TEST_CLOCK).minusDays(1)) // Expired yesterday
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add approved item
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("100.00"))
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        return estimate.getId();
    }

    private UUID seedApprovedEstimateWithoutApprovedItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-"
                        + UUID.fromString("00000000-0000-0000-0000-000000000001")
                                .toString()
                                .substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now(TEST_CLOCK))
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add items but all declined (not approved)
        EstimateItem item = EstimateItem.builder()
                .estimate(estimate)
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.0"))
                .unitPrice(new BigDecimal("100.00"))
                .lineTotal(new BigDecimal("100.00"))
                .approvalStatus(ApprovalStatus.DECLINED) // No approved items
                .createdById("test-user")
                .build();
        estimateItemRepository.save(item);

        return estimate.getId();
    }

    private void initTestIds() {
        if (testCustomerId == null) {
            testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        }
    }
}
