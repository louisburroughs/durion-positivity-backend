package com.positivity.shopmanager.contract;

import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.shopmanager.BaseContractIntegrationTest;
import com.positivity.shopmanager.PosShopManagerApplication;
import com.positivity.shopmanager.internal.client.CrmCustomerClient;
import com.positivity.shopmanager.internal.client.CrmVehicleClient;
import com.positivity.shopmanager.internal.exception.CrmCustomerNotFoundException;
import com.positivity.shopmanager.internal.exception.CrmVehicleNotFoundException;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.ResourceAccessException;

@SpringBootTest(classes = PosShopManagerApplication.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@ActiveProfiles("test")
// Issue #75 start: H2 in-memory datasource + disable external startup
// dependencies for isolated integration tests
@TestPropertySource(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:shopmgr_cap137;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.cloud.discovery.enabled=false",
            "eureka.client.enabled=false",
            "spring.cloud.config.enabled=false",
            "spring.boot.admin.client.enabled=false",
            "pos.security.permission-registration.enabled=false"
        })
// Issue #75 end
@Tag("contract")
@DisplayName("CAP-137 Story #75: Create Appointment with CRM Integration — Contract Behavioral Tests")
class AppointmentCreateContractBehaviorIT extends BaseContractIntegrationTest {

    private static final UUID VALID_CRM_CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VALID_CRM_VEHICLE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID UNKNOWN_CRM_CUSTOMER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID UNKNOWN_CRM_VEHICLE_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID MISMATCHED_VEHICLE_ID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final UUID CRM_TIMEOUT_CUSTOMER_ID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
    private static final UUID TEST_LOCATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TEST_SERVICE_REQUEST_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID DIFFERENT_CUSTOMER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CrmCustomerClient crmCustomerClient;

    @MockitoBean
    private CrmVehicleClient crmVehicleClient;

    @BeforeEach
    void setupCrmStubs() {
        when(crmCustomerClient.getCustomerById(VALID_CRM_CUSTOMER_ID)).thenReturn(validCustomerSnapshot());
        when(crmCustomerClient.getCustomerById(UNKNOWN_CRM_CUSTOMER_ID))
                .thenThrow(new CrmCustomerNotFoundException(UNKNOWN_CRM_CUSTOMER_ID));
        when(crmCustomerClient.getCustomerById(CRM_TIMEOUT_CUSTOMER_ID))
                .thenThrow(new ResourceAccessException("Connection timeout"));

        when(crmVehicleClient.getVehicleById(VALID_CRM_VEHICLE_ID))
                .thenReturn(validVehicleSnapshot(VALID_CRM_CUSTOMER_ID));
        when(crmVehicleClient.getVehicleById(UNKNOWN_CRM_VEHICLE_ID))
                .thenThrow(new CrmVehicleNotFoundException(UNKNOWN_CRM_VEHICLE_ID));
        when(crmVehicleClient.getVehicleById(MISMATCHED_VEHICLE_ID))
                .thenReturn(validVehicleSnapshot(DIFFERENT_CUSTOMER_ID));
    }

    @Override
    protected String defaultAuthorities() {
        return "shop:schedule:edit,shop:schedule:view";
    }

    @Test
    @DisplayName("S1: should_return_201_with_SCHEDULED_status_when_valid_crm_appointment_created")
    void should_return_201_with_SCHEDULED_status_when_valid_crm_appointment_created() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").isNotEmpty())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.crmCustomerId").value(VALID_CRM_CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.crmVehicleId").value(VALID_CRM_VEHICLE_ID.toString()))
                .andExpect(jsonPath("$.serviceRequestIds[0]").value(TEST_SERVICE_REQUEST_ID.toString()));
    }

    @Test
    @DisplayName("S2: should_return_appointmentId_in_uuid_format_as_proxy_for_event_emission")
    void should_return_appointmentId_in_uuid_format_as_proxy_for_event_emission() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId")
                        .value(matchesPattern("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")));
    }

    @Test
    @DisplayName("S3: should_return_400_when_crmCustomerId_is_missing_from_request")
    void should_return_400_when_crmCustomerId_is_missing_from_request() throws Exception {
        String payloadMissingCustomer = """
                                {
                                  "crmVehicleId":      "%s",
                                  "locationId":        "%s",
                                  "resourceId":        "BAY-01",
                                  "startAt":           "2026-04-01T14:00:00Z",
                                  "endAt":             "2026-04-01T15:00:00Z",
                                  "serviceRequestIds": ["%s"]
                                }
                                """.formatted(VALID_CRM_VEHICLE_ID, TEST_LOCATION_ID, TEST_SERVICE_REQUEST_ID);

        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadMissingCustomer)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("S4: should_return_404_CUSTOMER_NOT_FOUND_when_crmCustomerId_does_not_exist_in_crm")
    void should_return_404_CUSTOMER_NOT_FOUND_when_crmCustomerId_does_not_exist_in_crm() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(UNKNOWN_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CUSTOMER_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("S5: should_return_404_VEHICLE_NOT_FOUND_when_crmVehicleId_does_not_exist_in_crm")
    void should_return_404_VEHICLE_NOT_FOUND_when_crmVehicleId_does_not_exist_in_crm() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(VALID_CRM_CUSTOMER_ID, UNKNOWN_CRM_VEHICLE_ID))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("VEHICLE_NOT_FOUND"))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("S6: should_return_400_when_startAt_is_after_endAt")
    void should_return_400_when_startAt_is_after_endAt() throws Exception {
        // startAt (16:00) is after endAt (15:00) — invalid range
        String payloadInvalidTimeRange =
                """
                                {
                                  "crmCustomerId":     "%s",
                                  "crmVehicleId":      "%s",
                                  "locationId":        "%s",
                                  "resourceId":        "BAY-01",
                                  "startAt":           "2026-04-01T16:00:00Z",
                                  "endAt":             "2026-04-01T15:00:00Z",
                                  "serviceRequestIds": ["%s"]
                                }
                                """.formatted(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID, TEST_LOCATION_ID, TEST_SERVICE_REQUEST_ID);

        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadInvalidTimeRange)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("S7: should_return_409_VEHICLE_CUSTOMER_MISMATCH_when_vehicle_belongs_to_different_customer")
    void should_return_409_VEHICLE_CUSTOMER_MISMATCH_when_vehicle_belongs_to_different_customer() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(VALID_CRM_CUSTOMER_ID, MISMATCHED_VEHICLE_ID))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VEHICLE_CUSTOMER_MISMATCH"))
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    @DisplayName("S8: should_return_503_CRM_UNAVAILABLE_when_crm_timeout_occurs")
    void should_return_503_CRM_UNAVAILABLE_when_crm_timeout_occurs() throws Exception {
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(buildValidCreateRequest(CRM_TIMEOUT_CUSTOMER_ID, VALID_CRM_VEHICLE_ID))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CRM_UNAVAILABLE"))
                .andExpect(jsonPath("$.status").value(503));
    }

    @Test
    @DisplayName("S9: should_return_400_when_startAt_is_missing_from_request")
    void should_return_400_when_startAt_is_missing_from_request() throws Exception {
        String payloadMissingStartAt =
                """
                                {
                                  \"crmCustomerId\":     \"%s\",
                                  \"crmVehicleId\":      \"%s\",
                                  \"locationId\":        \"%s\",
                                  \"resourceId\":        \"BAY-01\",
                                  \"endAt\":             \"2026-04-01T15:00:00Z\",
                                  \"serviceRequestIds\": [\"%s\"]
                                }
                                """.formatted(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID, TEST_LOCATION_ID, TEST_SERVICE_REQUEST_ID);

        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadMissingStartAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("S10: should_return_400_when_endAt_is_missing_from_request")
    void should_return_400_when_endAt_is_missing_from_request() throws Exception {
        String payloadMissingEndAt =
                """
                                {
                                  \"crmCustomerId\":     \"%s\",
                                  \"crmVehicleId\":      \"%s\",
                                  \"locationId\":        \"%s\",
                                  \"resourceId\":        \"BAY-01\",
                                  \"startAt\":           \"2026-04-01T14:00:00Z\",
                                  \"serviceRequestIds\": [\"%s\"]
                                }
                                """.formatted(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID, TEST_LOCATION_ID, TEST_SERVICE_REQUEST_ID);

        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadMissingEndAt)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("S11: should_return_400_when_serviceRequestIds_is_empty")
    void should_return_400_when_serviceRequestIds_is_empty() throws Exception {
        String payloadWithEmptyServiceRequestIds =
                """
                                {
                                  "crmCustomerId":     "%s",
                                  "crmVehicleId":      "%s",
                                  "locationId":        "%s",
                                  "resourceId":        "BAY-01",
                                  "startAt":           "2026-04-01T14:00:00Z",
                                  "endAt":             "2026-04-01T15:00:00Z",
                                  "serviceRequestIds": []
                                }
                                """.formatted(VALID_CRM_CUSTOMER_ID, VALID_CRM_VEHICLE_ID, TEST_LOCATION_ID);

        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadWithEmptyServiceRequestIds)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private Map<String, Object> validCustomerSnapshot() {
        return Map.of(
                "id", VALID_CRM_CUSTOMER_ID.toString(),
                "fullName", "Contract Customer",
                "phone", "555-000-0000",
                "email", "customer@example.com");
    }

    private Map<String, Object> validVehicleSnapshot(UUID customerId) {
        return Map.of(
                "id",
                VALID_CRM_VEHICLE_ID.toString(),
                "ownerCustomerId",
                customerId.toString(),
                "year",
                2024,
                "make",
                "Durion",
                "model",
                "ServiceVan");
    }

    private String buildValidCreateRequest(UUID crmCustomerId, UUID crmVehicleId) {
        return """
                                {
                                  "crmCustomerId":     "%s",
                                  "crmVehicleId":      "%s",
                                  "locationId":        "%s",
                                  "resourceId":        "BAY-01",
                                  "startAt":           "2026-04-01T14:00:00Z",
                                  "endAt":             "2026-04-01T15:00:00Z",
                                  "serviceRequestIds": ["%s"]
                                }
                                """.formatted(crmCustomerId, crmVehicleId, TEST_LOCATION_ID, TEST_SERVICE_REQUEST_ID);
    }
}
