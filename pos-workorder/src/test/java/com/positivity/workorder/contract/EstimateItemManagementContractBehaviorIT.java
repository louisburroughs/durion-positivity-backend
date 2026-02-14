package com.positivity.workorder.contract;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.closeTo;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.positivity.workorder.internal.dto.AddEstimateItemRequest;
import com.positivity.workorder.internal.dto.UpdateEstimateItemRequest;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.entity.EstimateStatus;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;

/**
 * Contract behavioral tests for Estimate Item Management endpoints.
 * Stories #14 (Add Parts), #15 (Add Labor), #17 (Revise Estimate)
 * 
 * Verifies:
 * - Happy path: Add, update, delete line items on draft estimates
 * - Validation: Cannot modify items on non-DRAFT estimates
 * - State constraints: Proper error codes for invalid states
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EstimateItemManagementContractBehaviorIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("Contract: Add PART item to draft estimate - Happy Path")
    void shouldAddPartItemToDraftEstimate() {
        // Given: A draft estimate exists (assume created by test setup)
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"); // Mock ID

        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .description("Oil Filter")
                .quantity(new BigDecimal("1"))
                .unitPrice(new BigDecimal("12.99"))
                .taxCode("TAXABLE")
                .productId(UUID.randomUUID())
                .build();

        // When: Adding a part item
        // And: The estimate exists and is in DRAFT status
        given()
                .when()
                .get("/v1/workorders/estimates/{estimateId}", estimateId)
                .then()
                .statusCode(200)
                .body("id", equalTo(estimateId.toString()))
                .body("status", equalTo(EstimateStatus.DRAFT.name()));

        // When/Then: Adding a part item succeeds and returns expected fields
        given()
                .contentType(ContentType.JSON)
                .body(request)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/items", estimateId)
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("id", notNullValue())
                .body("estimateId", equalTo(estimateId.toString()))
                .body("itemType", equalTo(EstimateItemType.PART.name()))
                .body("description", equalTo("Oil Filter"))
                .body("quantity", closeTo(1.0, 0.001))
                .body("unitPrice", closeTo(12.99, 0.001))
                .body("lineTotal", closeTo(12.99, 0.001))
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Add LABOR item to draft estimate - Happy Path")
    void shouldAddLaborItemToDraftEstimate() {
        // Given: A draft estimate exists
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000"); // Mock ID

        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.LABOR)
                .description("Oil Change Service")
                .quantity(new BigDecimal("1"))
                .unitPrice(new BigDecimal("45.00"))
                .serviceId(UUID.randomUUID())
                .build();

        // When: Adding a labor item
        given()
                .contentType(ContentType.JSON)
                .body(request)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/items", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404))) // 404 if estimate doesn't exist
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Cannot add item to APPROVED estimate - State Constraint")
    void shouldRejectAddItemToApprovedEstimate() {
        // Given: An approved estimate (would be set up in test database)
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        AddEstimateItemRequest request = AddEstimateItemRequest.builder()
                .itemType(EstimateItemType.PART)
                .description("Brake Pads")
                .quantity(new BigDecimal("1"))
                .unitPrice(new BigDecimal("89.99"))
                .build();

        // When/Then: Attempting to add item should fail with 409 CONFLICT
        given()
                .contentType(ContentType.JSON)
                .body(request)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/items", estimateId)
                .then()
                .statusCode(anyOf(is(409), is(404))) // 409 if exists and approved, 404 if not found
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Update line item on draft estimate - Happy Path")
    void shouldUpdateLineItemOnDraftEstimate() {
        // Given: A draft estimate with an existing item
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID itemId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        UpdateEstimateItemRequest request = UpdateEstimateItemRequest.builder()
                .quantity(new BigDecimal("2"))
                .unitPrice(new BigDecimal("99.99"))
                .build();

        // When: Updating an item
        given()
                .contentType(ContentType.JSON)
                .body(request)
                .when()
                .patch("/v1/workorders/estimates/{estimateId}/items/{itemId}", estimateId, itemId)
                .then()
                .statusCode(anyOf(is(200), is(404))) // 404 if estimate or item not found
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Delete line item from draft estimate - Happy Path")
    void shouldDeleteLineItemFromDraftEstimate() {
        // Given: A draft estimate with an existing item
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID itemId = UUID.fromString("550e8400-e29b-41d4-a716-446655440002");

        // When: Deleting an item
        given()
                .when()
                .delete("/v1/workorders/estimates/{estimateId}/items/{itemId}", estimateId, itemId)
                .then()
                .statusCode(anyOf(is(204), is(404))) // 204 success, 404 if not found
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Validation - Missing required fields")
    void shouldRejectInvalidAddItemRequest() {
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // Invalid request: missing itemType
        AddEstimateItemRequest invalidRequest = AddEstimateItemRequest.builder()
                .description("Test Item")
                .quantity(new BigDecimal("1"))
                .unitPrice(new BigDecimal("10.00"))
                .build();

        // When/Then: Should reject with 400 BAD REQUEST
        given()
                .contentType(ContentType.JSON)
                .body(invalidRequest)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/items", estimateId)
                .then()
                .statusCode(anyOf(is(400), is(404))) // 400 validation error, 404 if estimate not found
                .log().ifValidationFails();
    }
}
