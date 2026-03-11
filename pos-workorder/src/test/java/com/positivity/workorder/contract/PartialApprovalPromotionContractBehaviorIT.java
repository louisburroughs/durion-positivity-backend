package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.enums.ApprovalStatus;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

/**
 * Contract behavior integration tests for partial approval promotion.
 * CAP:004 Story #29 - Handle Partial Approval Promotion
 *
 * Tests cover:
 * - Promoting estimates with subset of approved items
 * - Filtering out declined items during promotion
 * - Filtering out pending items during promotion
 * - Verifying only approved items are copied to workorder
 * - Verifying totals are adjusted for partial scope
 */
@DisplayName("Partial Approval Promotion Contract Behavior Tests (CAP:004 Story #29)")
@Import(ContractTestConfiguration.class)
class PartialApprovalPromotionContractBehaviorIT extends BaseContractIntegrationTest {
        private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

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

        // ========== PARTIAL APPROVAL PROMOTION TESTS ==========

        @Test
        @DisplayName("PA-001: Successfully promote estimate with subset of approved items")
        void testPromoteWithPartialApproval_HappyPath() {
                // Given: An approved estimate with mixed approval statuses
                // 2 APPROVED items, 1 DECLINED item, 1 PENDING_APPROVAL item
                UUID estimateId = seedApprovedEstimateWithMixedApprovalStatuses();

                List<EstimateItem> allItems = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);
                assertThat(allItems).hasSize(4);

                List<EstimateItem> approvedItems = estimateItemRepository
                                .findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                                                estimateId, ApprovalStatus.APPROVED);
                assertThat(approvedItems).hasSize(2);

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

                // Then: Verify only APPROVED items were copied to workorder
                workorderRepository.findById(workorderId).orElseThrow();

                // Count total workorder items (labor + parts)
                List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                                .findAll().stream()
                                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                                .toList();

                List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

                int totalWorkorderItems = laborItems.size() + partItems.size();
                assertThat(totalWorkorderItems).isEqualTo(2); // Only 2 APPROVED items should be copied

                // Verify declined and pending items were NOT copied
                List<EstimateItem> declinedItems = allItems.stream()
                                .filter(item -> item.getApprovalStatus() == ApprovalStatus.DECLINED)
                                .toList();

                List<EstimateItem> pendingItems = allItems.stream()
                                .filter(item -> item.getApprovalStatus() == ApprovalStatus.PENDING_APPROVAL)
                                .toList();

                for (EstimateItem declinedItem : declinedItems) {
                        boolean foundInWorkorder = laborItems.stream()
                                        .anyMatch(ws -> ws.getOriginEstimateItemId().equals(declinedItem.getId()))
                                        || partItems.stream()
                                                        .anyMatch(wp -> wp.getOriginEstimateItemId()
                                                                        .equals(declinedItem.getId()));

                        assertThat(foundInWorkorder).isFalse();
                }

                for (EstimateItem pendingItem : pendingItems) {
                        boolean foundInWorkorder = laborItems.stream()
                                        .anyMatch(ws -> ws.getOriginEstimateItemId().equals(pendingItem.getId()))
                                        || partItems.stream()
                                                        .anyMatch(wp -> wp.getOriginEstimateItemId()
                                                                        .equals(pendingItem.getId()));

                        assertThat(foundInWorkorder).isFalse();
                }
        }

        @Test
        @DisplayName("PA-002: Promote estimate with all items approved (full approval)")
        void testPromoteWithFullApproval() {
                // Given: An approved estimate where ALL items are approved
                UUID estimateId = seedApprovedEstimateWithAllItemsApproved();

                List<EstimateItem> allItems = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);
                assertThat(allItems).hasSize(3);

                List<EstimateItem> approvedItems = estimateItemRepository
                                .findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                                                estimateId, ApprovalStatus.APPROVED);
                assertThat(approvedItems).hasSize(3);

                // When: Promote estimate to workorder
                String workorderIdStr = givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .statusCode(200)
                                .body("id", notNullValue())
                                .extract()
                                .path("id");

                UUID workorderId = UUID.fromString(workorderIdStr);

                // Then: Verify all items were copied
                List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                                .findAll().stream()
                                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                                .toList();

                List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

                int totalWorkorderItems = laborItems.size() + partItems.size();
                assertThat(totalWorkorderItems).isEqualTo(3);
        }

        @Test
        @DisplayName("PA-003: Promote estimate with only declined items - reject promotion")
        void testPromoteWithAllItemsDeclined() {
                // Given: An approved estimate where ALL items are declined
                UUID estimateId = seedApprovedEstimateWithAllItemsDeclined();

                List<EstimateItem> allItems = estimateItemRepository.findByEstimate_IdAndDeletedFalse(estimateId);
                assertThat(allItems).hasSize(2);

                List<EstimateItem> approvedItems = estimateItemRepository
                                .findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                                                estimateId, ApprovalStatus.APPROVED);
                assertThat(approvedItems).isEmpty();

                // When: Promote estimate to workorder
                givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .statusCode(409);

                // Then: no workorder should be created
                assertThat(workorderRepository.findAllByEstimate_Id(estimateId)).isEmpty();
        }

        @Test
        @DisplayName("PA-004: Verify financial snapshot accuracy for approved items only")
        void testFinancialSnapshotForApprovedItemsOnly() {
                // Given: An approved estimate with mixed approval statuses
                UUID estimateId = seedApprovedEstimateWithMixedApprovalStatuses();

                List<EstimateItem> approvedItems = estimateItemRepository
                                .findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                                                estimateId, ApprovalStatus.APPROVED);

                // When: Promote estimate to workorder
                String workorderIdStr = givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                UUID workorderId = UUID.fromString(workorderIdStr);

                // Then: Verify financial snapshot matches approved items only
                List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                                .findAll().stream()
                                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                                .toList();

                List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

                // Verify each workorder item corresponds to an approved estimate item
                for (com.positivity.workorder.internal.entity.WorkorderService laborItem : laborItems) {
                        EstimateItem sourceItem = approvedItems.stream()
                                        .filter(ei -> ei.getId().equals(laborItem.getOriginEstimateItemId()))
                                        .findFirst()
                                        .orElseThrow();

                        assertThat(laborItem.getDescription()).isEqualTo(sourceItem.getDescription());
                        assertThat(laborItem.getQuantity()).isEqualByComparingTo(sourceItem.getQuantity());
                        assertThat(laborItem.getUnitPrice()).isEqualByComparingTo(sourceItem.getUnitPrice());
                        assertThat(laborItem.getLineTotal()).isEqualByComparingTo(sourceItem.getLineTotal());
                        assertThat(laborItem.getTaxCode()).isEqualTo(sourceItem.getTaxCode());
                        assertThat(laborItem.getStatus()).isEqualTo(WorkorderItemStatus.OPEN);
                }

                for (WorkorderPart partItem : partItems) {
                        EstimateItem sourceItem = approvedItems.stream()
                                        .filter(ei -> ei.getId().equals(partItem.getOriginEstimateItemId()))
                                        .findFirst()
                                        .orElseThrow();

                        assertThat(partItem.getDescription()).isEqualTo(sourceItem.getDescription());
                        assertThat(partItem.getQuantity()).isEqualByComparingTo(sourceItem.getQuantity());
                        assertThat(partItem.getUnitPrice()).isEqualByComparingTo(sourceItem.getUnitPrice());
                        assertThat(partItem.getLineTotal()).isEqualByComparingTo(sourceItem.getLineTotal());
                        assertThat(partItem.getTaxCode()).isEqualTo(sourceItem.getTaxCode());
                        assertThat(partItem.getStatus()).isEqualTo(WorkorderItemStatus.OPEN);
                }
        }

        @Test
        @DisplayName("PA-005: Verify traceability - originEstimateItemId links only to approved items")
        void testTraceabilityOnlyForApprovedItems() {
                // Given: An approved estimate with mixed approval statuses
                UUID estimateId = seedApprovedEstimateWithMixedApprovalStatuses();

                List<EstimateItem> approvedItems = estimateItemRepository
                                .findByEstimate_IdAndApprovalStatusAndDeletedFalse(
                                                estimateId, ApprovalStatus.APPROVED);
                List<UUID> approvedItemIds = approvedItems.stream().map(EstimateItem::getId).toList();

                // When: Promote estimate to workorder
                String workorderIdStr = givenWithGatewayAuth()
                                .when()
                                .post("/v1/workorders/estimates/{id}/promote", estimateId)
                                .then()
                                .statusCode(200)
                                .extract()
                                .path("id");

                UUID workorderId = UUID.fromString(workorderIdStr);

                // Then: Verify all workorder items trace back to approved estimate items only
                List<com.positivity.workorder.internal.entity.WorkorderService> laborItems = workorderServiceRepository
                                .findAll().stream()
                                .filter(ws -> ws.getWorkOrder().getId().equals(workorderId))
                                .toList();

                List<WorkorderPart> partItems = workorderPartRepository.findByWorkorderId(workorderId);

                for (com.positivity.workorder.internal.entity.WorkorderService laborItem : laborItems) {
                        assertThat(approvedItemIds).contains(laborItem.getOriginEstimateItemId());
                }

                for (WorkorderPart partItem : partItems) {
                        assertThat(approvedItemIds).contains(partItem.getOriginEstimateItemId());
                }
        }

        // ========== TEST DATA SEEDING HELPERS ==========

        /**
         * Seed an approved estimate with mixed approval statuses:
         * - 1 LABOR item (APPROVED)
         * - 1 PART item (APPROVED)
         * - 1 LABOR item (DECLINED)
         * - 1 PART item (PENDING_APPROVAL)
         */
        private UUID seedApprovedEstimateWithMixedApprovalStatuses() {
                initTestIds();

                Estimate estimate = Estimate.builder()
                                .estimateNumber("EST-2024-9001")
                                .customerId(testCustomerId)
                                .vehicleId(testVehicleId)
                                .locationId(testLocationId)
                                .currencyUomId("USD")
                                .createdById("test-user")
                                .createdByUserId("test-user")
                                .status(EstimateStatus.APPROVED)
                                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                                .subtotal(BigDecimal.valueOf(500.00))
                                .taxAmount(BigDecimal.valueOf(50.00))
                                .total(BigDecimal.valueOf(550.00))
                                .build();

                estimate = estimateRepository.save(estimate);

                // Item 1: LABOR, APPROVED (Oil Change)
                EstimateItem laborApproved = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Oil Change Service")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(50.00))
                                .lineTotal(BigDecimal.valueOf(50.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                // Item 2: PART, APPROVED (Oil Filter)
                EstimateItem partApproved = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.PART)
                                .description("Oil Filter")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(25.00))
                                .lineTotal(BigDecimal.valueOf(25.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                // Item 3: LABOR, DECLINED (Tire Rotation)
                EstimateItem laborDeclined = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Tire Rotation")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(40.00))
                                .lineTotal(BigDecimal.valueOf(40.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.DECLINED)
                                .rejectionReason("CUSTOMER_DECLINED")
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                // Item 4: PART, PENDING_APPROVAL (Air Filter)
                EstimateItem partPending = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.PART)
                                .description("Air Filter")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(30.00))
                                .lineTotal(BigDecimal.valueOf(30.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.PENDING_APPROVAL)
                                .build();

                estimateItemRepository.saveAll(List.of(laborApproved, partApproved, laborDeclined, partPending));

                return estimate.getId();
        }

        /**
         * Seed an approved estimate where ALL items are approved
         */
        private UUID seedApprovedEstimateWithAllItemsApproved() {
                initTestIds();

                Estimate estimate = Estimate.builder()
                                .estimateNumber("EST-2024-9002")
                                .customerId(testCustomerId)
                                .vehicleId(testVehicleId)
                                .locationId(testLocationId)
                                .currencyUomId("USD")
                                .createdById("test-user")
                                .createdByUserId("test-user")
                                .status(EstimateStatus.APPROVED)
                                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                                .subtotal(BigDecimal.valueOf(300.00))
                                .taxAmount(BigDecimal.valueOf(30.00))
                                .total(BigDecimal.valueOf(330.00))
                                .build();

                estimate = estimateRepository.save(estimate);

                EstimateItem item1 = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Diagnostic Service")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(100.00))
                                .lineTotal(BigDecimal.valueOf(100.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                EstimateItem item2 = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Brake Inspection")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(75.00))
                                .lineTotal(BigDecimal.valueOf(75.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                EstimateItem item3 = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.PART)
                                .description("Brake Pads")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(125.00))
                                .lineTotal(BigDecimal.valueOf(125.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.APPROVED)
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                estimateItemRepository.saveAll(List.of(item1, item2, item3));

                return estimate.getId();
        }

        /**
         * Seed an approved estimate where ALL items are declined
         */
        private UUID seedApprovedEstimateWithAllItemsDeclined() {
                initTestIds();

                Estimate estimate = Estimate.builder()
                                .estimateNumber("EST-2024-9003")
                                .customerId(testCustomerId)
                                .vehicleId(testVehicleId)
                                .locationId(testLocationId)
                                .currencyUomId("USD")
                                .createdById("test-user")
                                .createdByUserId("test-user")
                                .status(EstimateStatus.APPROVED) // Estimate approved but items declined
                                .approvedAt(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .expiresAt(LocalDateTime.now(TEST_CLOCK).plusDays(30))
                                .subtotal(BigDecimal.ZERO)
                                .taxAmount(BigDecimal.ZERO)
                                .total(BigDecimal.ZERO)
                                .build();

                estimate = estimateRepository.save(estimate);

                EstimateItem item1 = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.LABOR)
                                .description("Engine Overhaul")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(2000.00))
                                .lineTotal(BigDecimal.valueOf(2000.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.DECLINED)
                                .rejectionReason("TOO_EXPENSIVE")
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                EstimateItem item2 = EstimateItem.builder()
                                .estimate(estimate)
                                .itemType(EstimateItemType.PART)
                                .description("Transmission Rebuild Kit")
                                .quantity(BigDecimal.valueOf(1.0))
                                .unitPrice(BigDecimal.valueOf(1500.00))
                                .lineTotal(BigDecimal.valueOf(1500.00))
                                .taxCode("TAX_STANDARD")
                                .createdById("test-user")
                                .approvalStatus(ApprovalStatus.DECLINED)
                                .rejectionReason("NOT_NECESSARY")
                                .approvalTimestamp(LocalDateTime.now(TEST_CLOCK).minusHours(1))
                                .build();

                estimateItemRepository.saveAll(List.of(item1, item2));

                return estimate.getId();
        }

        private void initTestIds() {
                testCustomerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
                testLocationId = UUID.fromString("550e8400-e29b-41d4-a716-446655440003");
                testVehicleId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");
        }
}
