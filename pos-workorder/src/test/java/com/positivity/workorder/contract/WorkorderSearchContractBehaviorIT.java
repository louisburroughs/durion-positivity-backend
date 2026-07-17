package com.positivity.workorder.contract;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Contract behavioral tests for the free-text workorder search endpoint.
 *
 * <p>Exercises {@code GET /v1/workorders/search?q=...} which matches the customer
 * name or workorder id and enriches each row with a resolved {@code customerName}.
 * The pos-customer service is not reachable in tests, so {@code customerName} falls
 * back to {@code customer-<id>} — assertions only require the field to be present.
 */
class WorkorderSearchContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private WorkorderRepository workorderRepository;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    @Test
    @DisplayName("Contract: q search by workorder id returns the workorder with customerName")
    void shouldSearchByWorkorderIdAndIncludeCustomerName() {
        UUID workorderId = seedWorkorder();

        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/search?q={q}", workorderId.toString())
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("content.size()", greaterThanOrEqualTo(1))
                .body("content[0].workorderId", equalTo(workorderId.toString()))
                .body("content[0].customerName", notNullValue())
                .log()
                .ifValidationFails();
    }

    @Test
    @DisplayName("Contract: customerId/vehicleId filters restrict search results")
    void shouldFilterByCustomerIdAndVehicleId() {
        UUID customerA = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        UUID vehicleA = UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        UUID customerB = UUID.fromString("00000000-0000-0000-0000-0000000000ba");
        UUID vehicleB = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID matching = seedWorkorder(customerA, vehicleA);
        seedWorkorder(customerB, vehicleB);

        givenWithGatewayAuth()
                .when()
                .get("/v1/workorders/search?customerId={c}&vehicleId={v}", customerA.toString(), vehicleA.toString())
                .then()
                .statusCode(200)
                .contentType(startsWith("application/json"))
                .body("content.size()", equalTo(1))
                .body("content[0].workorderId", equalTo(matching.toString()))
                .log()
                .ifValidationFails();
    }

    private UUID seedWorkorder() {
        return seedWorkorder(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000001"));
    }

    private UUID seedWorkorder(UUID customerId, UUID vehicleId) {
        Workorder workorder = Workorder.builder()
                .customerId(customerId)
                .vehicleId(vehicleId)
                .shopId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .status(WorkorderStatus.DRAFT)
                .build();

        return workorderRepository.save(workorder).getId();
    }
}
