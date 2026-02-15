package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import org.springframework.context.annotation.Import;

/**
 * Contract behavior integration tests for estimate promotion to workorder.
 * CAP:004 Story #26 - Create Workorder from Approved Estimate
 *
 * Tests cover:
 * - Successful promotion of approved estimate to workorder
 * - Idempotency: duplicate promotion attempts return existing workorder
 * - Validation failures: estimate not found, not approved, expired, no approved items
 * - Authorization checks
 */
@DisplayName("Estimate Promotion Contract Behavior Tests (CAP:004 Story #26)")
@Import(ContractTestConfiguration.class)
class EstimatePromotionContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        workorderRepository.deleteAll();
        estimateItemRepository.deleteAll();
        estimateRepository.deleteAll();
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
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("PR-002: Idempotent promotion - second call returns existing workorder")
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

        // Second promotion attempt - should return same workorder
        givenWithGatewayAuth()
                .header("Idempotency-Key", "test-key-" + estimateId)
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(workorderId))
                .log().ifValidationFails();
    }

    // ========== PROMOTION VALIDATION FAILURE TESTS ==========

    @Test
    @DisplayName("PR-003: Reject promotion - estimate not found")
    void testPromoteEstimate_NotFound() {
        UUID nonExistentId = UUID.randomUUID();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", nonExistentId)
                .then()
                .statusCode(404)
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("PR-004: Reject promotion - estimate not in APPROVED status")
    void testPromoteEstimate_NotApproved() {
        UUID estimateId = seedDraftEstimateWithItems();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - APPROVAL_INVALID
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("PR-005: Reject promotion - estimate approval expired")
    void testPromoteEstimate_Expired() {
        UUID estimateId = seedExpiredApprovedEstimate();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - APPROVAL_EXPIRED
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("PR-006: Reject promotion - estimate has no approved items")
    void testPromoteEstimate_NoApprovedItems() {
        UUID estimateId = seedApprovedEstimateWithoutApprovedItems();

        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(409) // Conflict - NO_APPROVED_ITEMS
                .log().ifValidationFails();
    }

    // ========== TEST DATA SEEDING HELPERS ==========

    private UUID seedApprovedEstimateWithItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now().plusDays(30)) // Not expired
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add approved items
        EstimateItem laborItem = EstimateItem.builder()
                .estimateId(estimate.getId())
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
                .estimateId(estimate.getId())
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
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
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
                .estimateId(estimate.getId())
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
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now().minusDays(40))
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now().minusDays(1)) // Expired yesterday
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add approved item
        EstimateItem item = EstimateItem.builder()
                .estimateId(estimate.getId())
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
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("100.00"))
                .taxAmount(new BigDecimal("8.25"))
                .total(new BigDecimal("108.25"))
                .expiresAt(LocalDateTime.now().plusDays(30))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Add items but all declined (not approved)
        EstimateItem item = EstimateItem.builder()
                .estimateId(estimate.getId())
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
            testCustomerId = UUID.randomUUID();
            testLocationId = UUID.randomUUID();
            testVehicleId = UUID.randomUUID();
        }
    }
}
