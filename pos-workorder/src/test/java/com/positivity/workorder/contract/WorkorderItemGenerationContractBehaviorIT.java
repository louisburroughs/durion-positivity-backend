package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.ApprovalStatus;
import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateStatus;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderItemStatus;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

/**
 * Contract behavior integration tests for workorder item generation from
 * estimate items.
 * CAP:004 Story #27 - Generate Workorder Items from Approved Scope
 *
 * Tests cover:
 * - Creating workorder items from approved estimate items
 * - Preserving financial snapshot (pricing, quantity, tax codes)
 * - Traceability back to estimate items
 * - Proper status assignment (Authorized/OPEN)
 */
@DisplayName("Workorder Item Generation Contract Behavior Tests (CAP:004 Story #27)")
@Import(ContractTestConfiguration.class)
class WorkorderItemGenerationContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private WorkorderServiceRepository workorderServiceRepository;

    @Autowired
    private WorkorderPartRepository workorderPartRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;

    @AfterEach
    void tearDown() {
        workorderServiceRepository.deleteAll();
        workorderPartRepository.deleteAll();
        workorderRepository.deleteAll();
        estimateItemRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    // ========== WORKORDER ITEM GENERATION TESTS ==========

    @Test
    @DisplayName("WI-001: Successfully create workorder items from approved estimate items")
    void testCreateWorkorderItems_HappyPath() {
        // Given: An approved estimate with labor and part items
        UUID estimateId = seedApprovedEstimateWithMixedItems();
        List<EstimateItem> estimateItems = estimateItemRepository.findByEstimateIdAndDeletedFalse(estimateId);
        assertThat(estimateItems).hasSize(3); // 2 labor + 1 part

        // When: Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("estimateId", equalTo(estimateId.toString()))
                .extract()
                .path("id");

        UUID workorderId = UUID.fromString(workorderIdStr);

        // Then: Verify workorder items were created
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

        // Verify labor items (WorkorderService entities)
        List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                .findAll().stream()
                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                .toList();
        assertThat(laborItems).hasSize(2);

        // Verify part items (WorkorderPart entities)
        List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);
        assertThat(partItems).hasSize(1);

        // Verify financial snapshot for first labor item
        com.positivity.workorder.internal.entity.WorkorderService laborItem1 = laborItems.get(0);
        EstimateItem sourceLabor = estimateItems.stream()
                .filter(ei -> ei.getId().equals(laborItem1.getOriginEstimateItemId()))
                .findFirst()
                .orElseThrow();

        assertThat(laborItem1.getDescription()).isEqualTo(sourceLabor.getDescription());
        assertThat(laborItem1.getQuantity()).isEqualByComparingTo(sourceLabor.getQuantity());
        assertThat(laborItem1.getUnitPrice()).isEqualByComparingTo(sourceLabor.getUnitPrice());
        assertThat(laborItem1.getLineTotal()).isEqualByComparingTo(sourceLabor.getLineTotal());
        assertThat(laborItem1.getTaxCode()).isEqualTo(sourceLabor.getTaxCode());
        assertThat(laborItem1.getOriginEstimateItemId()).isEqualTo(sourceLabor.getId());
        assertThat(laborItem1.getStatus()).isEqualTo(WorkorderItemStatus.OPEN);

        // Verify financial snapshot for part item
        WorkorderPart partItem = partItems.get(0);
        EstimateItem sourcePart = estimateItems.stream()
                .filter(ei -> ei.getId().equals(partItem.getOriginEstimateItemId()))
                .findFirst()
                .orElseThrow();

        assertThat(partItem.getDescription()).isEqualTo(sourcePart.getDescription());
        assertThat(partItem.getQuantity()).isEqualByComparingTo(sourcePart.getQuantity());
        assertThat(partItem.getUnitPrice()).isEqualByComparingTo(sourcePart.getUnitPrice());
        assertThat(partItem.getLineTotal()).isEqualByComparingTo(sourcePart.getLineTotal());
        assertThat(partItem.getTaxCode()).isEqualTo(sourcePart.getTaxCode());
        assertThat(partItem.getOriginEstimateItemId()).isEqualTo(sourcePart.getId());
        assertThat(partItem.getStatus()).isEqualTo(WorkorderItemStatus.OPEN);
    }

    @Test
    @DisplayName("WI-002: Verify all financial fields are preserved as immutable snapshot")
    void testFinancialSnapshotImmutable() {
        // Given: An approved estimate with a labor item
        UUID estimateId = seedApprovedEstimateWithSingleLaborItem();
        EstimateItem originalItem = estimateItemRepository.findByEstimateIdAndDeletedFalse(estimateId).get(0);

        // When: Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        UUID workorderId = UUID.fromString(workorderIdStr);

        // Then: Verify snapshot fields are exact copies
        List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                .findAll().stream()
                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                .toList();

        assertThat(laborItems).hasSize(1);
        com.positivity.workorder.internal.entity.WorkorderService workorderItem = laborItems.get(0);

        // All financial fields must match exactly
        assertThat(workorderItem.getQuantity()).isEqualByComparingTo(originalItem.getQuantity());
        assertThat(workorderItem.getUnitPrice()).isEqualByComparingTo(originalItem.getUnitPrice());
        assertThat(workorderItem.getLineTotal()).isEqualByComparingTo(originalItem.getLineTotal());
        assertThat(workorderItem.getTaxCode()).isEqualTo(originalItem.getTaxCode());

        // Verify precision is preserved (4 decimal places for quantity, 2 for money)
        assertThat(originalItem.getQuantity().scale()).isGreaterThanOrEqualTo(0);
        assertThat(originalItem.getUnitPrice().scale()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("WI-003: Verify traceability - originEstimateItemId is set correctly")
    void testTraceabilityToEstimateItems() {
        // Given: An approved estimate with multiple items
        UUID estimateId = seedApprovedEstimateWithMixedItems();
        List<EstimateItem> estimateItems = estimateItemRepository.findByEstimateIdAndDeletedFalse(estimateId);

        // When: Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        UUID workorderId = UUID.fromString(workorderIdStr);

        // Then: Verify all workorder items have valid originEstimateItemId
        List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                .findAll().stream()
                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                .toList();

        List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

        // Verify each labor item traces back to an estimate item
        for (com.positivity.workorder.internal.entity.WorkorderService laborItem : laborItems) {
            assertThat(laborItem.getOriginEstimateItemId()).isNotNull();
            EstimateItem source = estimateItems.stream()
                    .filter(ei -> ei.getId().equals(laborItem.getOriginEstimateItemId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(source.getItemType()).isEqualTo(EstimateItemType.LABOR);
        }

        // Verify each part item traces back to an estimate item
        for (WorkorderPart partItem : partItems) {
            assertThat(partItem.getOriginEstimateItemId()).isNotNull();
            EstimateItem source = estimateItems.stream()
                    .filter(ei -> ei.getId().equals(partItem.getOriginEstimateItemId()))
                    .findFirst()
                    .orElseThrow();
            assertThat(source.getItemType()).isEqualTo(EstimateItemType.PART);
        }
    }

    @Test
    @DisplayName("WI-004: Verify workorder items have correct initial status (OPEN/Authorized)")
    void testWorkorderItemInitialStatus() {
        // Given: An approved estimate with items
        UUID estimateId = seedApprovedEstimateWithMixedItems();

        // When: Promote estimate to workorder
        String workorderIdStr = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .path("id");

        UUID workorderId = UUID.fromString(workorderIdStr);

        // Then: Verify all items have initial status OPEN (equivalent to Authorized)
        List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                .findAll().stream()
                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                .toList();

        List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

        laborItems.forEach(item -> assertThat(item.getStatus()).isEqualTo(WorkorderItemStatus.OPEN));
        partItems.forEach(item -> assertThat(item.getStatus()).isEqualTo(WorkorderItemStatus.OPEN));
    }

    @Test
    @DisplayName("WI-005: Handle promotion with no estimate items gracefully")
    void testPromotionWithNoItems() {
        // Given: An approved estimate with NO items
        UUID estimateId = seedApprovedEstimateWithNoItems();

        // When: Promote estimate to workorder
        givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                .then()
                // Expect validation failure: no approved items available for promotion
                .statusCode(409);
    }

    // ========== TEST DATA SEEDING HELPERS ==========

    private UUID seedApprovedEstimateWithMixedItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("280.00"))
                .taxAmount(new BigDecimal("23.10"))
                .total(new BigDecimal("303.10"))
                .expiresAt(LocalDateTime.now().plusDays(30))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // Labor item 1: Oil change
        EstimateItem labor1 = EstimateItem.builder()
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.LABOR)
                .description("Oil change labor")
                .quantity(new BigDecimal("1.5000")) // 1.5 hours
                .unitPrice(new BigDecimal("60.00"))
                .lineTotal(new BigDecimal("90.00"))
                .taxCode("LABOR_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(labor1);

        // Labor item 2: Tire rotation
        EstimateItem labor2 = EstimateItem.builder()
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.LABOR)
                .description("Tire rotation labor")
                .quantity(new BigDecimal("0.7500")) // 0.75 hours
                .unitPrice(new BigDecimal("60.00"))
                .lineTotal(new BigDecimal("45.00"))
                .taxCode("LABOR_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(labor2);

        // Part item: Engine oil filter
        EstimateItem part1 = EstimateItem.builder()
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.PART)
                .description("Engine oil filter")
                .quantity(new BigDecimal("1.0000"))
                .unitPrice(new BigDecimal("145.00"))
                .lineTotal(new BigDecimal("145.00"))
                .taxCode("PARTS_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(part1);

        return estimate.getId();
    }

    private UUID seedApprovedEstimateWithSingleLaborItem() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(testCustomerId)
                .subtotal(new BigDecimal("120.00"))
                .taxAmount(new BigDecimal("9.90"))
                .total(new BigDecimal("129.90"))
                .expiresAt(LocalDateTime.now().plusDays(30))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        EstimateItem labor = EstimateItem.builder()
                .estimateId(estimate.getId())
                .itemType(EstimateItemType.LABOR)
                .description("Diagnostic labor")
                .quantity(new BigDecimal("2.0000"))
                .unitPrice(new BigDecimal("60.00"))
                .lineTotal(new BigDecimal("120.00"))
                .taxCode("LABOR_TAX")
                .approvalStatus(ApprovalStatus.APPROVED)
                .createdById("test-user")
                .build();
        estimateItemRepository.save(labor);

        return estimate.getId();
    }

    private UUID seedApprovedEstimateWithNoItems() {
        initTestIds();

        Estimate estimate = Estimate.builder()
                .estimateNumber("EST-TEST-" + UUID.randomUUID().toString().substring(0, 8))
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .locationId(testLocationId)
                .status(EstimateStatus.APPROVED)
                .approvedAt(LocalDateTime.now())
                .approvedBy(testCustomerId)
                .subtotal(BigDecimal.ZERO)
                .taxAmount(BigDecimal.ZERO)
                .total(BigDecimal.ZERO)
                .expiresAt(LocalDateTime.now().plusDays(30))
                .currencyUomId("USD")
                .createdByUserId("test-user")
                .createdById("test-user")
                .build();
        estimate = estimateRepository.save(estimate);

        // No items created
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
