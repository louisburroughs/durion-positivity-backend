package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import com.positivity.workorder.internal.entity.Estimate;
import com.positivity.workorder.internal.enums.EstimateStatus;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract behavioral tests for the free-text estimate search endpoint.
 *
 * <p>Exercises {@code GET /v1/workexec/estimates/search?q=...} which matches the
 * estimate number, customer name, or estimate id and enriches each row with a
 * resolved {@code customerName}. The pos-customer service is not reachable in
 * tests, so {@code customerName} falls back to {@code customer-<id>} — the assertion
 * only requires the field to be present/non-null.
 */
class EstimateSearchContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private EstimateRepository estimateRepository;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    @Test
    @DisplayName("Contract: q search by estimate number returns the estimate with customerName")
    void shouldSearchByEstimateNumberAndIncludeCustomerName() {
        seedEstimate("EST-IT-1");

        givenWithGatewayAuth()
                .when()
                .get("/v1/workexec/estimates/search?q={q}", "EST-IT-1")
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("content.size()", greaterThanOrEqualTo(1))
                .body("content[0].estimateNumber", equalTo("EST-IT-1"))
                .body("content[0].customerName", notNullValue())
                .log()
                .ifValidationFails();
    }

    private UUID seedEstimate(String estimateNumber) {
        Estimate estimate = Estimate.builder()
                .estimateNumber(estimateNumber)
                .locationId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .vehicleId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .customerId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .currencyUomId("USD")
                .taxRegionId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .status(EstimateStatus.DRAFT)
                .subtotal(new BigDecimal("200.00"))
                .taxAmount(new BigDecimal("16.50"))
                .total(new BigDecimal("216.50"))
                .createdByUserId(SYSTEM_USER_ID)
                .createdById(SYSTEM_USER_ID)
                .build();

        return estimateRepository.save(estimate).getId();
    }
}
