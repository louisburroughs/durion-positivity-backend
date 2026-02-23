package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.AuditEvent;
import com.positivity.workorder.internal.entity.ChangeRequest;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.entity.WorkorderSnapshot;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.AuditEventRepository;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.internal.repository.WorkorderSnapshotRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Workorder Completion Contract Behavior Tests (CAP:006)")
@Import(ContractTestConfiguration.class)
class WorkorderCompletionContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private WorkorderRepository workorderRepository;

        @Autowired
        private WorkorderServiceRepository workorderServiceRepository;

        @Autowired
        private WorkorderPartRepository workorderPartRepository;

        @Autowired
        private ChangeRequestRepository changeRequestRepository;

        @Autowired
        private WorkorderSnapshotRepository workorderSnapshotRepository;

        @Autowired
        private AuditEventRepository auditEventRepository;

        @Autowired
        private ObjectMapper objectMapper;

        @AfterEach
        void tearDown() {
                changeRequestRepository.deleteAll();
                workorderPartRepository.deleteAll();
                workorderServiceRepository.deleteAll();
                workorderSnapshotRepository.deleteAll();
                auditEventRepository.deleteAll();
                workorderRepository.deleteAll();
        }

        @Test
        @DisplayName("CWC-001: completion preconditions expose pending approval-gated change request blockers")
        void completionPreconditions_ShouldReportPendingApprovalGatedChangeRequests() {
                UUID workorderId = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS, false);
                seedServiceItem(workorderId, WorkorderItemStatus.COMPLETED, new BigDecimal("120.00"));
                seedPartItem(workorderId, WorkorderItemStatus.COMPLETED, new BigDecimal("40.00"));
                seedPendingApprovalGatedChangeRequest(workorderId);

                givenWithGatewayAuth()
                                .when()
                                .get("/v1/workorders/" + workorderId + "/completion-preconditions")
                                .then()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("canComplete", equalTo(false))
                                .body("unresolvedApprovalGatedChangeRequests", equalTo(1))
                                .body("blockingReasons", hasSize(greaterThan(0)));
        }

        @Test
        @DisplayName("CWC-002: complete workorder finalizes billable scope snapshot and records completion")
        void completeWorkorder_ShouldFinalizeBillableSnapshotAndTransitionState() {
                UUID workorderId = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS, false);
                seedServiceItem(workorderId, WorkorderItemStatus.COMPLETED, new BigDecimal("180.00"));
                seedPartItem(workorderId, WorkorderItemStatus.CANCELLED, new BigDecimal("30.00"));

                Map<String, Object> request = Map.of(
                                "userId", SYSTEM_USER_ID.toString(),
                                "completionNotes", "All required repair operations completed");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/v1/workorders/{workorderId}/complete", workorderId)
                                .then()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("previousStatus", equalTo("WORK_IN_PROGRESS"))
                                .body("currentStatus", equalTo("COMPLETED"))
                                .body("completedAt", notNullValue());

                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
                assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.COMPLETED);
                assertThat(workorder.getCompletedAt()).isNotNull();
                assertThat(workorder.getCompletedBy()).isEqualTo(SYSTEM_USER_ID);

                List<WorkorderSnapshot> snapshots = workorderSnapshotRepository
                                .findByWorkorderIdOrderByCapturedAtDesc(workorderId);
                assertThat(snapshots)
                                .extracting(WorkorderSnapshot::getSnapshotType)
                                .contains("BILLABLE_SCOPE_FINALIZED");

                WorkorderSnapshot finalizedSnapshot = snapshots.stream()
                                .filter(snapshot -> "BILLABLE_SCOPE_FINALIZED".equals(snapshot.getSnapshotType()))
                                .findFirst()
                                .orElseThrow();

                try {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> payload = objectMapper.readValue(finalizedSnapshot.getSnapshotData(),
                                        Map.class);
                        assertThat(payload)
                                        .containsEntry("snapshotVersion", "1.0")
                                        .containsEntry("workorderId", workorderId.toString())
                                        .containsEntry("invoiceReady", true)
                                        .containsEntry("estimateId", workorder.getEstimateId().toString())
                                        .containsEntry("approvalId", workorder.getApprovalId().toString())
                                        .containsEntry("customerAccountId", workorder.getCustomerId().toString())
                                        .containsEntry("serviceLocationId", workorder.getShopId().toString())
                                        .containsEntry("serviceCount", 1)
                                        .containsEntry("partCount", 0);
                        assertThat(toBigDecimal(payload.get("serviceTotal"))).isEqualByComparingTo("180.00");
                        assertThat(toBigDecimal(payload.get("partTotal"))).isEqualByComparingTo("0.00");
                        assertThat(toBigDecimal(payload.get("grandTotal"))).isEqualByComparingTo("180.00");

                        @SuppressWarnings("unchecked")
                        List<Map<String, Object>> lineItems = (List<Map<String, Object>>) payload.get("lineItems");
                        assertThat(lineItems).hasSize(1);

                        Map<String, Object> lineItem = lineItems.get(0);
                        assertThat(lineItem)
                                        .containsEntry("itemType", "LABOR")
                                        .containsEntry("description", "Service line for completion contract test")
                                        .containsEntry("taxCategoryCode", "TAX_STANDARD")
                                        .containsEntry("taxable", true)
                                        .containsEntry("status", "COMPLETED");
                        assertThat(toBigDecimal(lineItem.get("lineTotal"))).isEqualByComparingTo("180.00");
                } catch (Exception e) {
                        throw new AssertionError("Failed to parse or validate billable scope snapshot payload", e);
                }

                List<AuditEvent> auditEvents = auditEventRepository
                                .findByEntityTypeAndEntityIdOrderByEventTimestampDesc("Workorder", workorderId);
                assertThat(auditEvents)
                                .extracting(AuditEvent::getEventType)
                                .contains("StateTransition");
        }

        @Test
        @DisplayName("CWC-003: complete workorder is blocked when service/part items are not terminal")
        void completeWorkorder_ShouldBlockWhenItemsNotTerminal() {
                UUID workorderId = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS, false);
                seedServiceItem(workorderId, WorkorderItemStatus.OPEN, new BigDecimal("95.00"));

                Map<String, Object> request = Map.of(
                                "userId", SYSTEM_USER_ID.toString(),
                                "completionNotes", "Attempt completion with open items");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/v1/workorders/{workorderId}/complete", workorderId)
                                .then()
                                .statusCode(400)
                                .body("message", notNullValue());

                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
                assertThat(workorder.getStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);
        }

        @Test
        @DisplayName("CWC-004: reopen completed workorder requires non-empty reason")
        void reopenWorkorder_ShouldRequireReason() {
                UUID workorderId = seedWorkorder(WorkorderStatus.COMPLETED, false);

                Map<String, Object> request = Map.of(
                                "userId", SYSTEM_USER_ID.toString(),
                                "reopenReason", "   ");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/v1/workorders/{workorderId}/reopen", workorderId)
                                .then()
                                .statusCode(400)
                                .body("message", notNullValue());
        }

        @Test
        @DisplayName("CWC-005: authorized controlled reopen marks completed workorder as reopened and audits action")
        void reopenWorkorder_ShouldMarkReopenedAndAudit() {
                UUID workorderId = seedWorkorder(WorkorderStatus.COMPLETED, false);

                Map<String, Object> request = Map.of(
                                "userId", SYSTEM_USER_ID.toString(),
                                "reopenReason", "Correcting part quantity before invoicing");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/v1/workorders/{workorderId}/reopen", workorderId)
                                .then()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("currentStatus", equalTo("COMPLETED"))
                                .body("isReopened", equalTo(true))
                                .body("reopenedAt", notNullValue());

                Workorder reopened = workorderRepository.findById(workorderId).orElseThrow();
                assertThat(reopened.getStatus()).isEqualTo(WorkorderStatus.COMPLETED);
                assertThat(reopened.getIsReopened()).isTrue();
                assertThat(reopened.getReopenedBy()).isEqualTo(SYSTEM_USER_ID);
                assertThat(reopened.getReopenReason()).isEqualTo("Correcting part quantity before invoicing");

                List<WorkorderSnapshot> snapshots = workorderSnapshotRepository
                                .findByWorkorderIdOrderByCapturedAtDesc(workorderId);
                assertThat(snapshots)
                                .extracting(WorkorderSnapshot::getSnapshotType)
                                .contains("BILLABLE_SCOPE_SUPERSEDED");

                List<AuditEvent> auditEvents = auditEventRepository
                                .findByEntityTypeAndEntityIdOrderByEventTimestampDesc("Workorder", workorderId);
                assertThat(auditEvents)
                                .extracting(AuditEvent::getEventType)
                                .contains("WORKORDER_REOPENED");
        }

        @Test
        @DisplayName("CWC-006: cannot reopen workorder unless status is COMPLETED")
        void reopenWorkorder_ShouldRejectNonCompletedWorkorders() {
                UUID workorderId = seedWorkorder(WorkorderStatus.WORK_IN_PROGRESS, false);

                Map<String, Object> request = Map.of(
                                "userId", SYSTEM_USER_ID.toString(),
                                "reopenReason", "Attempting invalid reopen");

                givenWithGatewayAuth()
                                .contentType(ContentType.JSON)
                                .body(request)
                                .when()
                                .post("/v1/workorders/{workorderId}/reopen", workorderId)
                                .then()
                                .statusCode(400)
                                .body("message", notNullValue());
        }

        private UUID seedWorkorder(WorkorderStatus status, boolean isReopened) {
                Workorder workorder = Workorder.builder()
                                .customerId(UUID.randomUUID())
                                .shopId(UUID.randomUUID())
                                .vehicleId(UUID.randomUUID())
                                .estimateId(UUID.randomUUID())
                                .approvalId(UUID.randomUUID())
                                .status(status)
                                .isReopened(isReopened)
                                .completedAt(status == WorkorderStatus.COMPLETED ? Instant.now().minusSeconds(120)
                                                : null)
                                .completedBy(status == WorkorderStatus.COMPLETED ? SYSTEM_USER_ID : null)
                                .build();

                Workorder saved = workorderRepository.save(workorder);
                return saved.getId();
        }

        private void seedServiceItem(UUID workorderId, WorkorderItemStatus status, BigDecimal lineTotal) {
                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                WorkorderService service = WorkorderService.builder()
                                .workOrder(workorder)
                                .serviceEntityId(UUID.randomUUID())
                                .description("Service line for completion contract test")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(lineTotal)
                                .lineTotal(lineTotal)
                                .taxCode("TAX_STANDARD")
                                .status(status)
                                .declined(false)
                                .isEmergencySafety(false)
                                .build();

                workorderServiceRepository.save(service);
        }

        private void seedPartItem(UUID workorderId, WorkorderItemStatus status, BigDecimal lineTotal) {
                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                WorkorderPart part = WorkorderPart.builder()
                                .workorder(workorder)
                                .productEntityId(UUID.randomUUID())
                                .description("Part line for completion contract test")
                                .quantity(new BigDecimal("1.0000"))
                                .unitPrice(lineTotal)
                                .lineTotal(lineTotal)
                                .status(status)
                                .declined(false)
                                .isEmergencySafety(false)
                                .quantityIssued(BigDecimal.ZERO)
                                .quantityConsumed(BigDecimal.ZERO)
                                .quantityReturned(BigDecimal.ZERO)
                                .build();

                workorderPartRepository.save(part);
        }

        private void seedPendingApprovalGatedChangeRequest(UUID workorderId) {
                ChangeRequest changeRequest = ChangeRequest.builder()
                                .workorderId(workorderId)
                                .requestedByUserId(SYSTEM_USER_ID)
                                .requestedAt(LocalDateTime.now())
                                .updatedAt(LocalDateTime.now())
                                .status(ChangeRequest.ChangeRequestStatus.AWAITING_ADVISOR_REVIEW)
                                .description("Pending advisor approval before completion")
                                .isApprovalGated(true)
                                .isEmergencyException(false)
                                .build();

                changeRequestRepository.save(changeRequest);
        }

        private static BigDecimal toBigDecimal(Object value) {
                if (value instanceof Number number) {
                        return new BigDecimal(number.toString());
                }
                return new BigDecimal(String.valueOf(value));
        }
}
