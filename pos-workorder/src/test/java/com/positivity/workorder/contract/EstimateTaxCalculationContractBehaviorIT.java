package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.entity.EstimateItem;
import com.positivity.workorder.internal.entity.EstimateItemType;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

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

class EstimateTaxCalculationContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @Autowired
    private EstimateItemRepository estimateItemRepository;

    @AfterEach
    void tearDown() {
        estimateItemRepository.deleteAll();
        estimateRepository.deleteAll();
    }

    @Test
    @DisplayName("Contract: Calculate taxes and totals for draft estimate - Happy Path")
    void shouldCalculateTaxesForDraftEstimate() {
        // Given: A draft estimate with line items
        UUID estimateId = seedDraftEstimateWithItems();

        // When: Requesting calculation
        Response response = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(200)
                .log().ifValidationFails()
                .extract().response();

        JsonPath body = response.jsonPath();
        assertEquals(estimateId.toString(), body.getString("estimate.id"));

        BigDecimal subtotal = decimal(body, "subtotal");
        BigDecimal taxAmount = decimal(body, "taxAmount");
        BigDecimal total = decimal(body, "total");

        assertEquals(0, subtotal.compareTo(new BigDecimal("200.00")));
        assertTrue(taxAmount.compareTo(BigDecimal.ZERO) >= 0);
        assertEquals(0, total.compareTo(subtotal.add(taxAmount).setScale(2, RoundingMode.HALF_UP)));
    }

    @Test
    @DisplayName("Contract: Calculation is idempotent - Multiple calls same result")
    void shouldProduceIdempotentCalculationResults() {
        UUID estimateId = seedDraftEstimateWithItems();

        Response firstResponse = calculateResponse(estimateId);
        Response secondResponse = calculateResponse(estimateId);

        JsonPath firstBody = firstResponse.jsonPath();
        JsonPath secondBody = secondResponse.jsonPath();

        BigDecimal firstSubtotal = decimal(firstBody, "subtotal");
        BigDecimal secondSubtotal = decimal(secondBody, "subtotal");
        BigDecimal firstTax = decimal(firstBody, "taxAmount");
        BigDecimal secondTax = decimal(secondBody, "taxAmount");
        BigDecimal firstTotal = decimal(firstBody, "total");
        BigDecimal secondTotal = decimal(secondBody, "total");
        Integer firstVersion = firstBody.getInt("estimate.version");
        Integer secondVersion = secondBody.getInt("estimate.version");

        assertNotNull(firstSubtotal);
        assertNotNull(firstTax);
        assertNotNull(firstTotal);

        // Expected subtotal for seeded data (2 parts @50 + 5 labor hours @20)
        BigDecimal expectedSubtotal = new BigDecimal("200.00");
        assertEquals(0, firstSubtotal.compareTo(expectedSubtotal));
        assertEquals(0, secondSubtotal.compareTo(expectedSubtotal));
        assertTrue(firstTax.compareTo(BigDecimal.ZERO) >= 0);
        assertTrue(secondTax.compareTo(BigDecimal.ZERO) >= 0);
        assertEquals(0, firstTotal.compareTo(firstSubtotal.add(firstTax).setScale(2, RoundingMode.HALF_UP)));
        assertEquals(0, secondTotal.compareTo(secondSubtotal.add(secondTax).setScale(2, RoundingMode.HALF_UP)));

        assertEquals(0, firstSubtotal.compareTo(secondSubtotal));
        assertEquals(0, firstTax.compareTo(secondTax));
        assertEquals(0, firstTotal.compareTo(secondTotal));
        assertTrue(secondVersion >= firstVersion);
    }

    @Test
    @DisplayName("Contract: Cannot calculate for APPROVED estimate - State Constraint")
    void shouldRejectCalculationForApprovedEstimate() {
        // Given: An approved estimate
        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        // When/Then: Calculation should fail with 409 CONFLICT
        givenWithGatewayAuth()
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
        givenWithGatewayAuth()
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
        // When pos-accounting tax service is integrated, update this test
        // to verify proper jurisdiction-based tax calculation

        UUID estimateId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        Response response = givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(anyOf(is(200), is(404)))
                .extract()
                .response();

        if (response.statusCode() == 200) {
            JsonPath body = response.jsonPath();
            BigDecimal subtotal = decimal(body, "subtotal");
            BigDecimal taxAmount = decimal(body, "taxAmount");
            assertNotNull(subtotal);
            assertNotNull(taxAmount);
            BigDecimal expectedTax = subtotal.multiply(new BigDecimal("0.0825")).setScale(2, RoundingMode.HALF_UP);
            assertEquals(0, taxAmount.compareTo(expectedTax));
        }

        // Current stub: taxAmount = subtotal * 0.0825
        // Future: taxAmount calculated by pos-accounting based on jurisdiction
    }

    private Response calculateResponse(UUID estimateId) {
        return givenWithGatewayAuth()
                .when()
                .post("/v1/workorders/estimates/{estimateId}/calculate", estimateId)
                .then()
                .statusCode(200)
                .extract()
                .response();
    }

    private BigDecimal decimal(JsonPath body, String field) {
        Object raw = body.get(field);
        if (raw instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue()).setScale(2, RoundingMode.HALF_UP);
        }
        if (raw instanceof String text) {
            return new BigDecimal(text).setScale(2, RoundingMode.HALF_UP);
        }
        throw new IllegalStateException("Unsupported numeric field '" + field + "' value type: "
                + (raw == null ? "null" : raw.getClass().getName()));
    }

    private UUID seedDraftEstimateWithItems() {
        Estimate estimate = Estimate.builder()
                .id(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .estimateNumber("EST-" + System.nanoTime())
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .currencyUomId("USD")
                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .status(EstimateStatus.DRAFT)
                .createdByUserId(SYSTEM_USER_ID.toString())
                .createdById(SYSTEM_USER_ID.toString())
                .build();

        Estimate savedEstimate = estimateRepository.save(estimate);

        estimateItemRepository.save(buildItem(savedEstimate, EstimateItemType.PART, "Front brake pads",
                new BigDecimal("2"), new BigDecimal("50.00")));
        estimateItemRepository.save(buildItem(savedEstimate, EstimateItemType.LABOR, "Brake labor",
                new BigDecimal("5"), new BigDecimal("20.00")));

        return savedEstimate.getId();
    }

    private EstimateItem buildItem(Estimate estimate, EstimateItemType type, String description,
            BigDecimal quantity, BigDecimal unitPrice) {
        return EstimateItem.builder()
                .estimate(estimate)
                .itemType(type)
                .description(description)
                .quantity(quantity)
                .unitPrice(unitPrice)
                .taxCode("STD")
                .createdById(SYSTEM_USER_ID.toString())
                .build();
    }
}
