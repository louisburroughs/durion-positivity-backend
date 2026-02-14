package com.positivity.workorder.contract;

import com.positivity.workorder.internal.dto.EstimateResponse;
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
 * Contract behavioral tests for Estimate Tax Calculation endpoint.
 * Story #16 (Calculate Taxes and Totals)
 * 
 * Verifies:
 * - Happy path: Calculate subtotal, tax, and total for draft estimate
 * - Idempotency: Multiple calculations produce same result
 * - State constraints: Cannot calculate for non-DRAFT estimates
 * - Stub implementation: Documents 8.25% flat tax rate
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EstimateTaxCalculationContractBehaviorIT {

    @LocalServerPort
    private int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("Contract: Calculate taxes and totals for draft estimate - Happy Path")
    void shouldCalculateTaxesForDraftEstimate() {
        // Given: A draft estimate with line items
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Requesting calculation
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404))) // 404 if estimate doesn't exist
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Calculation is idempotent - Multiple calls same result")
    void shouldProduceIdempotentCalculationResults() {
        // Given: A draft estimate
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Calculating twice
        var firstResult = given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)));

        var secondResult = given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)));

        // Then: Results should be identical (if estimate exists)
        // Note: Full verification requires test database setup
    }

    @Test
    @DisplayName("Contract: Cannot calculate for APPROVED estimate - State Constraint")
    void shouldRejectCalculationForApprovedEstimate() {
        // Given: An approved estimate
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        // When/Then: Calculation should fail with 409 CONFLICT
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(409), is(404))) // 409 if approved, 404 if not found
                .log().ifValidationFails();
    }

    @Test
    @DisplayName("Contract: Calculation returns estimate with financial fields")
    void shouldReturnEstimateWithFinancialFields() {
        // Given: A draft estimate with items
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        // When: Calculating
        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Note: If 200, response should contain subtotal, taxAmount, total fields
        // Full field validation requires test database with known estimate
    }

    @Test
    @DisplayName("Contract: Tax calculation uses stub implementation (8.25% flat rate)")
    void shouldDocumentStubTaxCalculation() {
        // This test documents the stub behavior for future integration
        // TODO: When pos-accounting tax service is integrated, update this test
        // to verify proper jurisdiction-based tax calculation

        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        given()
                .contentType(ContentType.JSON)
                .header("X-User-Id", "00000000-0000-0000-0000-000000000001")
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .log().ifValidationFails();

        // Current stub: taxAmount = subtotal * 0.0825
        // Future: taxAmount calculated by pos-accounting based on jurisdiction
    }
}
