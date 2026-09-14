package com.positivity.workorder.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import com.positivity.workorder.internal.entity.ExtBayReplica;
import com.positivity.workorder.internal.entity.ExtMobileUnitReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ExtBayReplicaRepository;
import com.positivity.workorder.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.workorder.internal.repository.ServicePositionAssignmentRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;
import io.restassured.http.ContentType;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

/**
 * Service-position assignment end to end (#1983, #1984): assign → change → release, the hold
 * position, and the refusals a dispatcher can actually hit.
 *
 * <p>The one-open-workorder rule is asserted here at its application layer — the 409 naming the
 * occupant. Its database half, the partial unique index that decides two simultaneous assigns, is
 * asserted against real PostgreSQL by {@code AssignmentConstraintTest}; this contract runs on H2,
 * which has no partial indexes, so a test here could not tell the two apart.
 */
@DisplayName("Service Position Contract Behavior Tests (#1983, #1984)")
@Import(ContractTestConfiguration.class)
class ServicePositionContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID SITE = UUID.fromString("00000000-0000-0000-0000-0000000019a1");
    private static final UUID OTHER_SITE = UUID.fromString("00000000-0000-0000-0000-0000000019a2");
    private static final UUID BAY = UUID.fromString("00000000-0000-0000-0000-0000000019a3");
    private static final UUID OTHER_BAY = UUID.fromString("00000000-0000-0000-0000-0000000019a4");
    private static final UUID FOREIGN_BAY = UUID.fromString("00000000-0000-0000-0000-0000000019a5");
    private static final UUID MOBILE_UNIT = UUID.fromString("00000000-0000-0000-0000-0000000019a6");

    private static final String URL = "/v1/workorders/{workorderId}/position";

    @Autowired
    private WorkorderRepository workorderRepository;

    @Autowired
    private ServicePositionAssignmentRepository positionRepository;

    @Autowired
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Autowired
    private ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    @Autowired
    private com.positivity.workorder.internal.service.WorkorderStateMachine stateMachine;

    @AfterEach
    void tearDown() {
        purgeTestData();
    }

    /** A workorder at {@link #SITE}, with the site's bays and unit present in the replicas. */
    private UUID seedWorkorderAtSite(WorkorderStatus status) {
        extBayReplicaRepository.save(bay(BAY, SITE));
        extBayReplicaRepository.save(bay(OTHER_BAY, SITE));
        extBayReplicaRepository.save(bay(FOREIGN_BAY, OTHER_SITE));
        extMobileUnitReplicaRepository.save(ExtMobileUnitReplica.builder()
                .mobileUnitId(MOBILE_UNIT)
                .baseLocationId(SITE)
                .name("Van 1")
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build());

        Workorder workorder = Workorder.builder()
                .customerId(UUID.fromString("00000000-0000-0000-0000-0000000019b1"))
                .vehicleId(UUID.fromString("00000000-0000-0000-0000-0000000019b2"))
                .status(status)
                .locationId(SITE)
                .shopId(SITE)
                .build();
        return workorderRepository.save(workorder).getId();
    }

    private static ExtBayReplica bay(UUID bayId, UUID locationId) {
        return ExtBayReplica.builder()
                .bayId(bayId)
                .locationId(locationId)
                .name("Bay " + bayId.toString().substring(32))
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(Instant.EPOCH)
                .build();
    }

    private void assignPosition(UUID workorderId, Map<String, Object> body, int expectedStatus) {
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .put(URL, workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(expectedStatus);
    }

    @Test
    @DisplayName("SP-001: #1983 assign a bay, change to another, then release — each step kept as history")
    void assignChangeRelease() {
        UUID workorderId = seedWorkorderAtSite(WorkorderStatus.APPROVED);

        assignPosition(workorderId, Map.of("resourceType", "BAY", "resourceId", BAY.toString()), 200);
        assignPosition(
                workorderId,
                Map.of("resourceType", "BAY", "resourceId", OTHER_BAY.toString(), "reason", "Lift needed"),
                200);

        givenWithGatewayAuth()
                .when()
                .delete(URL + "?reason=Vehicle%20collected", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("resourceId", nullValue())
                .body("resourceType", nullValue());

        assertThat(workorderRepository.findById(workorderId).orElseThrow().getResourceId())
                .isNull();
        // Three placements: two taken and given up, one released outright. Nothing is deleted.
        assertThat(positionRepository.findByWorkorder_IdOrderByAssignedAtDescIdDesc(workorderId))
                .hasSize(2)
                .allSatisfy(placement -> assertThat(placement.getCurrent()).isFalse());
        assertThat(positionRepository.findByWorkorder_IdAndCurrentTrue(workorderId))
                .isEmpty();
    }

    @Test
    @DisplayName("SP-002: #1984 a bay another open workorder holds is refused with 409 RESOURCE_OCCUPIED")
    void occupiedBayIsRefused() {
        UUID first = seedWorkorderAtSite(WorkorderStatus.WORK_IN_PROGRESS);
        UUID second = seedWorkorderAtSite(WorkorderStatus.APPROVED);

        assignPosition(first, Map.of("resourceType", "BAY", "resourceId", BAY.toString()), 200);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("resourceType", "BAY", "resourceId", BAY.toString()))
                .when()
                .put(URL, second)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(409)
                .body("code", equalTo("RESOURCE_OCCUPIED"))
                .body("referenceId", equalTo(first.toString()));

        assertThat(workorderRepository.findById(second).orElseThrow().getResourceId())
                .isNull();
    }

    @Test
    @DisplayName("SP-003: #1984 the hold position takes every workorder the site can park")
    void holdHasNoCapacityLimit() {
        UUID first = seedWorkorderAtSite(WorkorderStatus.AWAITING_PARTS);
        UUID second = seedWorkorderAtSite(WorkorderStatus.AWAITING_PARTS);

        for (UUID workorderId : new UUID[] {first, second}) {
            givenWithGatewayAuth()
                    .contentType(ContentType.JSON)
                    .body(Map.of("resourceType", "HOLD", "reason", "Awaiting parts"))
                    .when()
                    .put(URL, workorderId)
                    .then()
                    .log()
                    .ifValidationFails()
                    .statusCode(200)
                    // A hold is identified by the site, which is what makes it site-scoped without a
                    // resource aggregate of its own.
                    .body("resourceType", equalTo("HOLD"))
                    .body("resourceId", equalTo(SITE.toString()));
        }
    }

    @Test
    @DisplayName("SP-004: #1984 cancelling a workorder frees its bay for the next one")
    void closingFreesTheBay() {
        UUID first = seedWorkorderAtSite(WorkorderStatus.WORK_IN_PROGRESS);
        UUID second = seedWorkorderAtSite(WorkorderStatus.APPROVED);
        assignPosition(first, Map.of("resourceType", "BAY", "resourceId", BAY.toString()), 200);

        // Driven through the state machine rather than an endpoint on purpose: it is the single
        // funnel every status change goes through, so this asserts the release hook itself rather
        // than one route's completion preconditions. Cancellation and completion take the same path.
        stateMachine.transitionWorkorder(first, WorkorderStatus.CANCELLED, "advisor", "Customer withdrew");

        // The bay is free because closing released it, not because anybody asked.
        assignPosition(second, Map.of("resourceType", "BAY", "resourceId", BAY.toString()), 200);

        assertThat(workorderRepository.findById(first).orElseThrow().getResourceId())
                .isNull();
        assertThat(positionRepository.findByWorkorder_IdAndCurrentTrue(first)).isEmpty();
        assertThat(positionRepository.findByWorkorder_IdOrderByAssignedAtDescIdDesc(first))
                .singleElement()
                .satisfies(placement -> {
                    // Closed, not deleted: where the job was when it ended survives the release.
                    assertThat(placement.getCurrent()).isFalse();
                    assertThat(placement.getReleasedAt()).isNotNull();
                    assertThat(placement.getResourceId()).isEqualTo(BAY);
                });
    }

    @Test
    @DisplayName("SP-005: #1983 a bay at another site, and an unknown one, are both 422")
    void foreignAndUnknownPositionsAreUnprocessable() {
        UUID workorderId = seedWorkorderAtSite(WorkorderStatus.APPROVED);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("resourceType", "BAY", "resourceId", FOREIGN_BAY.toString()))
                .when()
                .put(URL, workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(422)
                .body("code", equalTo("SERVICE_POSITION_INVALID"));

        assignPosition(
                workorderId, Map.of("resourceType", "BAY", "resourceId", "00000000-0000-0000-0000-0000000019ff"), 422);
    }

    @Test
    @DisplayName("SP-006: #1983 a cancelled workorder can no longer be moved")
    void closedWorkorderIsRefused() {
        UUID workorderId = seedWorkorderAtSite(WorkorderStatus.APPROVED);
        Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();
        workorder.setStatus(WorkorderStatus.CANCELLED);
        workorderRepository.save(workorder);

        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("resourceType", "BAY", "resourceId", BAY.toString()))
                .when()
                .put(URL, workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(409)
                .body("code", equalTo("WORKORDER_CLOSED"));
    }

    @Test
    @DisplayName("SP-007: #1983 GET answers with the position and the technician together")
    void readsPositionAndTechnician() {
        UUID workorderId = seedWorkorderAtSite(WorkorderStatus.APPROVED);
        assignPosition(workorderId, Map.of("resourceType", "MOBILE_UNIT", "resourceId", MOBILE_UNIT.toString()), 200);

        UUID technicianId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        givenWithGatewayAuth()
                .contentType(ContentType.JSON)
                .body(Map.of("technicianId", technicianId.toString()))
                .when()
                .post("/v1/workorders/{workorderId}/technician", workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200);

        givenWithGatewayAuth()
                .when()
                .get(URL, workorderId)
                .then()
                .log()
                .ifValidationFails()
                .statusCode(200)
                .body("resourceType", equalTo(ResourceType.MOBILE_UNIT.name()))
                .body("resourceId", equalTo(MOBILE_UNIT.toString()))
                // Assigning a technician never moved the position, and assigning the position never
                // touched the technician: two independent assignments, read as one answer.
                .body("technicianId", equalTo(technicianId.toString()))
                .body("history.size()", equalTo(1));
    }
}
