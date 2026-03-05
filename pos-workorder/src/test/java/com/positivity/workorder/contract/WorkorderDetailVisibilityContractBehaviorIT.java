package com.positivity.workorder.contract;

import java.time.ZoneOffset;
import java.time.Instant;
import java.time.Clock;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

import com.positivity.workorder.internal.entity.Customer;
import com.positivity.workorder.internal.entity.Vehicle;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderPart;
import com.positivity.workorder.internal.entity.WorkorderService;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.CustomerRepository;
import com.positivity.workorder.internal.repository.EstimateItemRepository;
import com.positivity.workorder.internal.repository.EstimateRepository;
import com.positivity.workorder.internal.repository.TechnicianAssignmentRepository;
import com.positivity.workorder.internal.repository.VehicleRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderPartRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import com.positivity.workorder.support.BaseContractIntegrationTest;

import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;

/**
 * Contract behavior integration tests for workorder detail visibility with
 * role-based access.
 * CAP:005 Story #155 - Role-Based Visibility
 *
 * Tests cover:
 * - Full detail with all authorities (financials visible, all capabilities
 * enabled)
 * - Detail without financial authority (financial fields null)
 * - Detail with minimal authorities (most capabilities false)
 * - Derived field calculations (isStarted, startedAt, totals)
 * - Capability flags reflect authorities correctly
 * - Nested service/part financial fields also omitted when
 * canViewFinancials=false
 */
@DisplayName("Workorder Detail Visibility Contract Behavior Tests (CAP:005 Story #155)")
@Import(ContractTestConfiguration.class)
class WorkorderDetailVisibilityContractBehaviorIT extends BaseContractIntegrationTest {
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

        @Autowired
        private WorkorderLaborEntryRepository laborEntryRepository;

        @Autowired
        private TechnicianAssignmentRepository technicianAssignmentRepository;

        @Autowired
        private CustomerRepository customerRepository;

        @Autowired
        private VehicleRepository vehicleRepository;

        @AfterEach
        void tearDown() {
                laborEntryRepository.deleteAll();
                technicianAssignmentRepository.deleteAll();
                workorderPartRepository.deleteAll();
                workorderServiceRepository.deleteAll();
                workorderRepository.deleteAll();
                estimateItemRepository.deleteAll();
                estimateRepository.deleteAll();
                customerRepository.deleteAll();
                vehicleRepository.deleteAll();
        }

        // ========== FULL AUTHORITIES TESTS ==========

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-001: Get detail with full authorities - all capabilities true, financials present")
        void testGetDetailWithFullAuthorities() {
                // Given: A workorder with services and parts
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // When: Request detail with all authorities
                givenWithAuthorities(
                                "workorder:workorder:view",
                                "workorder:workorder:start",
                                "workorder:workorder:approve",
                                "workorder:workorder:assign-technician",
                                "workorder:labor:add",
                                "workorder:parts:add",
                                "workorder:financials:view",
                                "workorder:workorder:edit",
                                "workorder:workorder:delete")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("status", notNullValue())
                                .body("customerId", notNullValue())
                                .body("vehicleId", notNullValue())
                                .body("capabilities.canStart", equalTo(true))
                                .body("capabilities.canApprove", equalTo(true))
                                .body("capabilities.canAssignTechnician", equalTo(true))
                                .body("capabilities.canRecordLabor", equalTo(true))
                                .body("capabilities.canRecordPartsUsage", equalTo(true))
                                .body("capabilities.canViewFinancials", equalTo(true))
                                .body("capabilities.canEditWorkorder", equalTo(true))
                                .body("capabilities.canDeleteWorkorder", equalTo(true))
                                // Financial fields should be present
                                .body("estimatedTotal", notNullValue())
                                .body("laborTotal", notNullValue())
                                .body("partsTotal", notNullValue())
                                // Services should have financial fields
                                .body("services", hasSize(greaterThan(0)))
                                .body("services[0].unitPrice", notNullValue())
                                .body("services[0].laborCost", notNullValue())
                                // Parts should have financial fields
                                .body("parts", hasSize(greaterThan(0)))
                                .body("parts[0].unitPrice", notNullValue())
                                .body("parts[0].partCost", notNullValue());
        }

        // ========== LIMITED AUTHORITIES TESTS ==========

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-002: Get detail without financial authority - financial fields null")
        void testGetDetailWithoutFinancialAuthority() {
                // Given: A workorder with services and parts
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // When: Request detail without financial authority
                givenWithAuthorities(
                                "workorder:workorder:view",
                                "workorder:labor:add",
                                "workorder:parts:add")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("capabilities.canViewFinancials", equalTo(false))
                                // Financial fields should be absent (null/not present)
                                .body("estimatedTotal", nullValue())
                                .body("laborTotal", nullValue())
                                .body("partsTotal", nullValue())
                                .body("taxTotal", nullValue())
                                // Services should have NO financial fields
                                .body("services", hasSize(greaterThan(0)))
                                .body("services[0].unitPrice", nullValue())
                                .body("services[0].laborCost", nullValue())
                                // Parts should have NO financial fields
                                .body("parts", hasSize(greaterThan(0)))
                                .body("parts[0].unitPrice", nullValue())
                                .body("parts[0].partCost", nullValue());
        }

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-003: Get detail with minimal authorities - most capabilities false")
        void testGetDetailWithMinimalAuthorities() {
                // Given: A workorder
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // When: Request detail with only view authority
                givenWithAuthorities("workorder:workorder:view")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("capabilities.canStart", equalTo(false))
                                .body("capabilities.canApprove", equalTo(false))
                                .body("capabilities.canAssignTechnician", equalTo(false))
                                .body("capabilities.canRecordLabor", equalTo(false))
                                .body("capabilities.canRecordPartsUsage", equalTo(false))
                                .body("capabilities.canViewFinancials", equalTo(false))
                                .body("capabilities.canEditWorkorder", equalTo(false))
                                .body("capabilities.canDeleteWorkorder", equalTo(false));
        }

        // ========== DERIVED FIELDS TESTS ==========

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-004: Verify derived fields calculated correctly")
        void testDerivedFieldsCalculation() {
                // Given: A workorder in WORK_IN_PROGRESS status
                UUID workorderId = seedWorkorderInProgress();

                // When: Request detail
                givenWithAuthorities("workorder:workorder:view")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("workorderId", equalTo(workorderId.toString()))
                                .body("status", equalTo("WORK_IN_PROGRESS"))
                                .body("isStarted", equalTo(true))
                                .body("isInProgress", equalTo(true))
                                .body("isCompleted", equalTo(false));
        }

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-005: Verify capability flags reflect authorities correctly")
        void testCapabilityFlagsAccuracy() {
                // Given: A workorder
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // When: Request with specific authorities
                givenWithAuthorities(
                                "workorder:workorder:view",
                                "workorder:workorder:start",
                                "workorder:labor:add")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("capabilities.canStart", equalTo(true))
                                .body("capabilities.canRecordLabor", equalTo(true))
                                .body("capabilities.canApprove", equalTo(false))
                                .body("capabilities.canAssignTechnician", equalTo(false))
                                .body("capabilities.canRecordPartsUsage", equalTo(false))
                                .body("capabilities.canViewFinancials", equalTo(false));
        }

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-006: Verify nested service/part financial fields omitted when canViewFinancials=false")
        void testNestedFinancialFieldsOmitted() {
                // Given: A workorder with services and parts
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // When: Request without financial authority
                givenWithAuthorities("workorder:workorder:view")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("capabilities.canViewFinancials", equalTo(false))
                                // Verify service financial fields are null
                                .body("services", hasSize(greaterThan(0)))
                                .body("services[0].description", notNullValue())
                                .body("services[0].unitPrice", nullValue())
                                .body("services[0].laborCost", nullValue())
                                .body("services[0].lineTotal", nullValue())
                                // Verify part financial fields are null
                                .body("parts", hasSize(greaterThan(0)))
                                .body("parts[0].description", notNullValue())
                                .body("parts[0].unitPrice", nullValue())
                                .body("parts[0].partCost", nullValue())
                                .body("parts[0].lineTotal", nullValue());
        }

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-007: Verify labor totals calculated from labor entries")
        void testLaborTotalsCalculation() {
                // Given: A workorder with labor entries
                UUID workorderId = seedWorkorderWithLaborEntries();

                // When: Request detail with financial authority
                givenWithAuthorities("workorder:workorder:view", "workorder:financials:view")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("capabilities.canViewFinancials", equalTo(true))
                                .body("services", hasSize(greaterThan(0)))
                                .body("services[0].totalLaborHours", notNullValue())
                                .body("services[0].laborCost", notNullValue());
        }

        @Test
        @Disabled("RestAssured NPE framework bug - see Story #157 for similar issue")
        @DisplayName("VIS-008: Verify parts usage totals aggregated correctly")
        void testPartsUsageTotalsAggregation() {
                // Given: A workorder with parts usage
                UUID workorderId = seedWorkorderWithPartsUsage();

                // When: Request detail
                givenWithAuthorities("workorder:workorder:view")
                                .when()
                                .get("/v1/workorders/{workorderId}/detail", workorderId)
                                .then()
                                .log().ifValidationFails()
                                .statusCode(200)
                                .body("parts", hasSize(greaterThan(0)))
                                .body("parts[0].quantityIssued", notNullValue())
                                .body("parts[0].quantityConsumed", notNullValue())
                                .body("parts[0].quantityReturned", notNullValue());
        }

        // ========== TEST DATA SEEDERS ==========

        private RequestSpecification givenWithAuthorities(String... authorities) {
                return RestAssured.given()
                                .contentType(ContentType.JSON)
                                .header("X-User-Id", SYSTEM_USER_ID.toString())
                                .header("X-Authorities", String.join(",", authorities));
        }

        private UUID seedWorkorderWithServicesAndParts() {
                // Create customer and vehicle
                Customer customer = Customer.builder()
                                .name("John Doe")
                                .email("john@example.com")
                                .phone("555-1234")
                                .build();
                customerRepository.save(customer);

                Vehicle vehicle = Vehicle.builder()
                                .vin("1HGBH41JXMN109186")
                                .make("Toyota")
                                .model("Camry")
                                .year("2020")
                                .build();
                vehicleRepository.save(vehicle);

                // Create workorder
                Workorder workorder = Workorder.builder()
                                .customerId(customer.getId())
                                .vehicleId(vehicle.getId())
                                .shopId(UUID.randomUUID())
                                .status(WorkorderStatus.APPROVED)
                                .build();
                workorderRepository.save(workorder);

                // Create service line item
                WorkorderService service = WorkorderService.builder()
                                .workOrder(workorder)
                                .serviceEntityId(UUID.randomUUID())
                                .description("Brake Pad Replacement")
                                .quantity(new BigDecimal("2.5"))
                                .unitPrice(new BigDecimal("85.00"))
                                .lineTotal(new BigDecimal("212.50"))
                                .status(WorkorderItemStatus.OPEN)
                                .build();
                workorderServiceRepository.save(service);

                // Create part line item
                WorkorderPart part = WorkorderPart.builder()
                                .workorder(workorder)
                                .workOrderService(service)
                                .productEntityId(UUID.randomUUID())
                                .description("Brake Rotor - Front")
                                .quantity(new BigDecimal("2.00"))
                                .unitPrice(new BigDecimal("65.00"))
                                .lineTotal(new BigDecimal("130.00"))
                                .status(WorkorderItemStatus.OPEN)
                                .quantityIssued(new BigDecimal("2.00"))
                                .quantityConsumed(BigDecimal.ZERO)
                                .quantityReturned(BigDecimal.ZERO)
                                .build();
                workorderPartRepository.save(part);

                return workorder.getId();
        }

        private UUID seedWorkorderInProgress() {
                Workorder workorder = Workorder.builder()
                                .customerId(UUID.randomUUID())
                                .vehicleId(UUID.randomUUID())
                                .shopId(UUID.randomUUID())
                                .status(WorkorderStatus.WORK_IN_PROGRESS)
                                .build();
                return workorderRepository.save(workorder).getId();
        }

        private UUID seedWorkorderWithLaborEntries() {
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // Reload workorder to avoid lazy initialization issues
                Workorder workorder = workorderRepository.findById(workorderId).orElseThrow();

                // Get the first service - need to load it eagerly
                List<com.positivity.workorder.internal.entity.WorkorderService> services = workorderServiceRepository
                                .findAll()
                                .stream()
                                .filter(s -> s.getWorkOrder() != null && s.getWorkOrder().getId().equals(workorderId))
                                .toList();

                if (services.isEmpty()) {
                        throw new IllegalStateException("No services found for workorder " + workorderId);
                }

                com.positivity.workorder.internal.entity.WorkorderService service = services.get(0);

                // Create labor entry
                WorkorderLaborEntry laborEntry = WorkorderLaborEntry.builder()
                                .workorder(workorder)
                                .workorderId(workorder.getId())
                                .workorderServiceId(service.getId())
                                .technicianId(UUID.randomUUID())
                                .startTime(LocalDateTime.now(TEST_CLOCK).minusHours(2))
                                .endTime(LocalDateTime.now(TEST_CLOCK))
                                .hoursWorked(new BigDecimal("2.0"))
                                .createdBy(SYSTEM_USER_ID)
                                .notes("Completed brake pad replacement")
                                .build();
                laborEntryRepository.save(laborEntry);

                return workorderId;
        }

        private UUID seedWorkorderWithPartsUsage() {
                UUID workorderId = seedWorkorderWithServicesAndParts();

                // Get services eagerly to avoid lazy initialization
                List<com.positivity.workorder.internal.entity.WorkorderService> services = workorderServiceRepository
                                .findAll()
                                .stream()
                                .filter(s -> s.getWorkOrder() != null && s.getWorkOrder().getId().equals(workorderId))
                                .toList();

                if (services.isEmpty()) {
                        throw new IllegalStateException("No services found for workorder " + workorderId);
                }

                com.positivity.workorder.internal.entity.WorkorderService service = services.get(0);

                // Get parts for this service
                List<WorkorderPart> parts = workorderPartRepository.findAll().stream()
                                .filter(p -> p.getWorkOrderService() != null
                                                && p.getWorkOrderService().getId().equals(service.getId()))
                                .toList();

                if (parts.isEmpty()) {
                        throw new IllegalStateException("No parts found for service " + service.getId());
                }

                // Update part with usage
                WorkorderPart part = parts.get(0);
                part.setQuantityIssued(new BigDecimal("2.00"));
                part.setQuantityConsumed(new BigDecimal("1.50"));
                part.setQuantityReturned(new BigDecimal("0.50"));
                workorderPartRepository.save(part);

                return workorderId;
        }
}
