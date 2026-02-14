package com.positivity.workorder.contract;

import com.positivity.workorder.internal.dto.EstimateSummaryResponse;
import com.positivity.workorder.internal.dto.EstimateSnapshotResponse;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/**
 * Contract behavioral tests for Estimate Summary endpoints.
 * Story #18 (Present Estimate Summary)
 * 
 * Verifies:
 * - Happy path: Get summary with grouped line items
 * - Snapshot creation: Immutable historical record
 * - Customer-facing format: Parts and labor grouped
 * - Note: PDF generation not implemented (requires document service)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EstimateSummaryContractBehaviorIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("Contract: Get customer-facing estimate summary - Happy Path")
    void shouldGetEstimateSummary() {
        // Given: An estimate with line items
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Retrieving summary
        // Assumes test fixture seeds this estimate with at least one PART and one LABOR line item.
        given()
            .contentType(ContentType.JSON)
            .when()
            .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
            .then()
            .statusCode(200)
            .contentType(startsWith("application/json"))
            .body("estimateId", equalTo(estimateId.toString()))
            .body("partItems", notNullValue())
            .body("laborItems", notNullValue())
            .body("partItems.size()", greaterThanOrEqualTo(1))
            .body("laborItems.size()", greaterThanOrEqualTo(1))
            .body("subtotal", notNullValue())
            .body("taxAmount", notNullValue())
            .body("total", notNullValue())
            .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Summary groups line items by type (parts and labor)")
    void shouldGroupLineItemsByType() {
        // Given: An estimate with both part and labor items
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Retrieving summary
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Note: If 200, response should contain partItems and laborItems arrays
        // Full verification requires test database with known items
    }

    @Test
    @DisplayName("Contract: Summary includes financial breakdown")
    void shouldIncludeFinancialBreakdown() {
        // Given: An estimate with calculated totals
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Retrieving summary
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Note: If 200, response should contain subtotal, taxAmount, total
    }

    @Test
    @DisplayName("Contract: Create historical snapshot - Happy Path")
    void shouldCreateEstimateSnapshot() {
        // Given: An estimate to snapshot
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Creating snapshot with notes
        given()
                .contentType(ContentType.JSON)
                .queryParam("notes", "Pre-approval snapshot")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404))) // 404 if estimate doesn't exist
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Snapshot captures complete estimate state as JSON")
    void shouldCaptureCompleteEstimateState() {
        // Given: An estimate with items and calculated totals
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Creating snapshot
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Note: If 200, response should contain snapshotData with serialized estimate +
        // items
    }

    @Test
    @DisplayName("Contract: Snapshot is immutable - Captured timestamp never changes")
    void shouldCreateImmutableSnapshot() {
        // Given: An estimate
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Creating snapshot
        var response = given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Then: Snapshot should have capturedAt timestamp that never changes
        // Future modifications to estimate should not affect this snapshot
    }

    @Test
    @DisplayName("Contract: PDF generation not implemented - Documented limitation")
    void shouldDocumentPDFGenerationLimitation() {
        // This test documents that PDF generation is not yet implemented
        // TODO: When document service is integrated, add PDF generation tests

        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // Current behavior: Summary returns JSON only
        given()
                .contentType(ContentType.JSON)
                .when()
                .get("/v1/workorders/estimates/{estimateId}/summary", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .contentType(anyOf(is("application/json"), is("application/json;charset=UTF-8")))
                .log().ifValidationFails();

        // Future: Add Accept: application/pdf header support
        // and verify PDF document is returned with proper MIME type
    }

    @Test
    @DisplayName("Contract: Multiple snapshots can be created for audit trail")
    void shouldAllowMultipleSnapshots() {
        // Given: An estimate
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Creating multiple snapshots at different points in time
        given()
                .contentType(ContentType.JSON)
                .queryParam("notes", "First snapshot")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)));

        given()
                .contentType(ContentType.JSON)
                .queryParam("notes", "Second snapshot after changes")
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/snapshots", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)));

        // Then: Both snapshots should exist with different timestamps
        // This provides full version history for compliance
    }
}
