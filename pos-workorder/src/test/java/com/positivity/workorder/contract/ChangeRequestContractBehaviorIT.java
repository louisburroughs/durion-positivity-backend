package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ChangeRequestRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

/**
 * Contract behavior integration tests for change request creation and approval
 * workflow.
 * CAP:005 - Story #156: Request Additional Work and Flag for Approval
 * 
 * Tests cover:
 * - Happy path: create change request with services and parts
 * - Idempotency: same key returns same resource (when implemented)
 * - Validation errors: missing description, no items
 * - List change requests by workorder
 * - Get change request by ID
 * - Invalid state: workorder not in WORK_IN_PROGRESS
 * 
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/156">Issue
 *      #156</a>
 */
@DisplayName("Change Request Contract Behavior Tests (CAP:005, Story #156)")
class ChangeRequestContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private ChangeRequestRepository changeRequestRepository;

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private EstimateRepository estimateRepository;

    private UUID testCustomerId;
    private UUID testLocationId;
    private UUID testVehicleId;
    private UUID testTechnicianId;
    private UUID testServiceEntityId;
    private UUID testProductEntityId;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    // ========== CHANGE REQUEST CREATION TESTS ==========

    @Test
    @DisplayName("CR-001: Successfully create change request with services and parts")
    void testCreateChangeRequest_HappyPath() {
        UUID workorderId = seedWorkorderInProgress();
        String idempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();

        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Technician discovered brake rotors below minimum thickness during pad replacement",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ],
                    "parts": [
                        {
                            "productEntityId": "%s",
                            "quantity": 2,
                            "isEmergencySafety": false,
                            "photoEvidenceUrl": "https://example.com/photos/brake-rotor-001.jpg"
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId, testProductEntityId);

        Response response = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .body("workorderId", equalTo(workorderId.toString()))
                .body("requestedByUserId", equalTo(SYSTEM_USER_ID))
                .body("status", equalTo("AWAITING_ADVISOR_REVIEW"))
                .body("description",
                        equalTo("Technician discovered brake rotors below minimum thickness during pad replacement"))
                .body("isEmergencyException", equalTo(false))
                .body("isApprovalGated", equalTo(true))
                .body("requestedAt", notNullValue())
                .log().ifValidationFails()
                .extract().response();

        String changeRequestId = response.path("id");

        // Verify change request was persisted
        org.assertj.core.api.Assertions.assertThat(changeRequestId).isNotNull();
        org.assertj.core.api.Assertions.assertThat(changeRequestRepository.findById(UUID.fromString(changeRequestId)))
                .isPresent();
    }

    @Test
    @DisplayName("CR-002: Retry with same idempotency key returns existing change request")
    void testCreateChangeRequest_WithSameIdempotencyKey_ReturnsExisting() {
        UUID workorderId = seedWorkorderInProgress();
        String idempotencyKey = UUID.fromString("00000000-0000-0000-0000-000000000001").toString();

        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Discovered worn suspension components",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId);

        // First request - creates change request
        Response firstResponse = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200)
                .body("id", notNullValue())
                .log().ifValidationFails()
                .extract().response();

        String firstChangeRequestId = firstResponse.path("id");

        // Second request - same key, should return existing change request
        // NOTE: This test will currently FAIL because idempotency is not yet
        // implemented
        // Once idempotency is implemented, this test should pass
        Response secondResponse = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .header("Idempotency-Key", idempotencyKey)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200)
                .body("id", equalTo(firstChangeRequestId))
                .log().ifValidationFails()
                .extract().response();

        String secondChangeRequestId = secondResponse.path("id");

        // Assert same change request returned
        org.assertj.core.api.Assertions.assertThat(secondChangeRequestId).isEqualTo(firstChangeRequestId);

        // Verify only one change request exists
        long count = changeRequestRepository.count();
        org.assertj.core.api.Assertions.assertThat(count).isEqualTo(1);
    }

    @Test
    @DisplayName("CR-003: Validation error - missing description")
    void testCreateChangeRequest_MissingDescription_ReturnsBadRequest() {
        UUID workorderId = seedWorkorderInProgress();

        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(400)
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("CR-004: Validation error - no items provided")
    void testCreateChangeRequest_NoItems_ReturnsBadRequest() {
        UUID workorderId = seedWorkorderInProgress();

        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Missing items",
                    "isEmergencyException": false
                }
                """, testTechnicianId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(400)
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("CR-005: Invalid state - workorder not in WORK_IN_PROGRESS")
    void testCreateChangeRequest_WorkorderNotInProgress_ReturnsConflict() {
        UUID workorderId = seedWorkorderInDraftState();

        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Attempt to request additional work on draft workorder",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(400)
                .log().ifValidationFails();
    }

    // ========== CHANGE REQUEST RETRIEVAL TESTS ==========

    @Test
    @DisplayName("CR-006: List change requests by workorder")
    void testGetChangeRequestsByWorkorder() {
        UUID workorderId = seedWorkorderInProgress();

        // Create first change request
        String requestBody1 = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "First change request",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody1)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200);

        // Create second change request
        String requestBody2 = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Second change request",
                    "isEmergencyException": false,
                    "parts": [
                        {
                            "productEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testProductEntityId);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody2)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200);

        // List all change requests for this workorder
        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200)
                .body("$", hasSize(2))
                .body("[0].workorderId", equalTo(workorderId.toString()))
                .body("[1].workorderId", equalTo(workorderId.toString()))
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("CR-007: Get change request by ID")
    void testGetChangeRequestById() {
        UUID workorderId = seedWorkorderInProgress();

        // Create change request
        String requestBody = String.format("""
                {
                    "requestedByUserId": "%s",
                    "description": "Test change request retrieval by ID",
                    "isEmergencyException": false,
                    "services": [
                        {
                            "serviceEntityId": "%s",
                            "quantity": 1,
                            "isEmergencySafety": false
                        }
                    ]
                }
                """, testTechnicianId, testServiceEntityId);

        Response createResponse = givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(requestBody)
                .when()
                .post("/v1/workorders/" + workorderId + "/changeRequests")
                .then()
                .statusCode(200)
                .extract().response();

        String changeRequestId = createResponse.path("id");

        // Retrieve by ID
        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/changeRequests/" + changeRequestId)
                .then()
                .statusCode(200)
                .body("id", equalTo(changeRequestId))
                .body("workorderId", equalTo(workorderId.toString()))
                .body("description", equalTo("Test change request retrieval by ID"))
                .body("status", equalTo("AWAITING_ADVISOR_REVIEW"))
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("CR-008: Get change request by ID - not found")
    void testGetChangeRequestById_NotFound() {
        UUID nonExistentId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/changeRequests/" + nonExistentId)
                .then()
                .statusCode(404)
                .log().ifValidationFails();
    }

    // ========== HELPER METHODS ==========

    /**
     * Seed a workorder in WORK_IN_PROGRESS status for change request testing.
     */
    private UUID seedWorkorderInProgress() {
        initializeTestData();

        // Create an estimate first (workorder references estimate)
        Estimate estimate = Estimate.builder()
                .customerId(testCustomerId)
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .status(EstimateStatus.APPROVED)
                .currencyUomId("USD")
                .subtotal(BigDecimal.valueOf(100.00))
                .taxAmount(BigDecimal.ZERO)
                .total(BigDecimal.valueOf(100.00))
                .createdById(SYSTEM_USER_ID)
                .build();
        Estimate savedEstimate = estimateRepository.save(estimate);

        // Create workorder in WORK_IN_PROGRESS status
        Workorder workorder = Workorder.builder()
                .estimate(savedEstimate)
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .shopId(testLocationId)
                .status(WorkorderStatus.WORK_IN_PROGRESS)
                .build();

        Workorder saved = workorderRepository.save(workorder);
        return saved.getId();
    }

    /**
     * Seed a workorder in DRAFT status for negative testing.
     */
    private UUID seedWorkorderInDraftState() {
        initializeTestData();

        // Create an estimate first (workorder references estimate)
        Estimate estimate = Estimate.builder()
                .customerId(testCustomerId)
                .locationId(testLocationId)
                .vehicleId(testVehicleId)
                .status(EstimateStatus.APPROVED)
                .currencyUomId("USD")
                .subtotal(BigDecimal.valueOf(100.00))
                .taxAmount(BigDecimal.ZERO)
                .total(BigDecimal.valueOf(100.00))
                .createdById(SYSTEM_USER_ID)
                .build();
        Estimate savedEstimate = estimateRepository.save(estimate);

        // Create workorder in DRAFT status
        Workorder workorder = Workorder.builder()
                .estimate(savedEstimate)
                .customerId(testCustomerId)
                .vehicleId(testVehicleId)
                .shopId(testLocationId)
                .status(WorkorderStatus.DRAFT)
                .build();

        Workorder saved = workorderRepository.save(workorder);
        return saved.getId();
    }

    /**
     * Initialize test data UUIDs.
     */
    private void initializeTestData() {
        testCustomerId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testLocationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testVehicleId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testTechnicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testServiceEntityId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        testProductEntityId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    }
}
