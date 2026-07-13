package com.positivity.shopmanager;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.positivity.shopmanager.internal.service.CrmSnapshotService;
import com.positivity.shopmanager.internal.service.StaffingScheduleService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Shop Manager Appointment Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CrmSnapshotService crmSnapshotService;

    @MockitoBean
    private StaffingScheduleService staffingScheduleService;

    // Fixed UUIDs shared across tests
    private static final String CUSTOMER_1 = "aaaaaaaa-0000-0000-0000-000000000001";
    private static final String CUSTOMER_2 = "aaaaaaaa-0000-0000-0000-000000000002";
    private static final String VEHICLE_1 = "bbbbbbbb-0000-0000-0000-000000000001";
    private static final String VEHICLE_2 = "bbbbbbbb-0000-0000-0000-000000000002";
    private static final String SERVICE_REQ = "cccccccc-0000-0000-0000-000000000001";
    private static final String LOCATION = "dddddddd-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        when(crmSnapshotService.getCustomerById(any())).thenReturn(Map.of("id", "customer-snapshot"));
        when(crmSnapshotService.getVehicleById(any())).thenReturn(Map.of("id", "vehicle-snapshot"));
    }

    @Override
    protected String defaultAuthorities() {
        return "appointments:create,appointments:view,appointments:reschedule,appointments:cancel,shop:schedule:edit,shop:schedule:view";
    }

    // ========== HAPPY PATH TESTS ==========

    @Test
    @DisplayName("CP-001: Successfully create appointment with valid fields")
    void testCreateAppointment_HappyPath() throws Exception {
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-01-27T19:30:00Z", "2026-01-27T20:30:00Z", "BAY-1");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000001")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appointmentId").exists())
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve appointment by ID")
    void testGetAppointment_HappyPath() throws Exception {
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-02-15T15:00:00Z", "2026-02-15T16:30:00Z", "BAY-2");
        MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000002")))
                .andExpect(status().isCreated())
                .andReturn();

        String appointmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("appointmentId")
                .asString();
        mockMvc.perform(withGatewayAuth(get("/v1/appointments/{id}", appointmentId)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appointmentId").value(appointmentId))
                .andExpect(jsonPath("$.locationId").value(LOCATION));
    }

    // ========== VALIDATION ERROR TESTS ==========

    @Test
    @DisplayName("VE-001: Reject appointment with invalid sourceType")
    void testCreateAppointment_InvalidSourceType() throws Exception {
        String invalidPayload = createAppointmentPayloadWithSourceType(
                CUSTOMER_1,
                VEHICLE_1,
                LOCATION,
                "2026-03-01T14:00:00Z",
                "2026-03-01T15:00:00Z",
                "BAY-1",
                "INVALID_TYPE",
                "SRC-001");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000003")))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("VE-002: Reject appointment with end time before start time")
    void testCreateAppointment_InvalidTimeRange() throws Exception {
        String invalidPayload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-03-15T20:00:00Z", "2026-03-15T19:00:00Z", "BAY-1");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000004")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-003: Reject appointment with empty serviceRequestIds")
    void testCreateAppointment_EmptyServiceRequestIds() throws Exception {
        String invalidPayload = String.format(
                "{\"crmCustomerId\":\"%s\",\"crmVehicleId\":\"%s\",\"locationId\":\"%s\","
                        + "\"startAt\":\"2026-04-01T18:00:00Z\",\"endAt\":\"2026-04-01T19:00:00Z\","
                        + "\"serviceRequestIds\":[],\"resourceId\":\"BAY-1\"}",
                CUSTOMER_1, VEHICLE_1, LOCATION);
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidPayload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000005")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ========== IDEMPOTENCY TEST ==========

    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateAppointment_Idempotent() throws Exception {
        String idempotencyKey = "idem-appt-" + System.currentTimeMillis();
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-04-20T16:00:00Z", "2026-04-20T17:30:00Z", "BAY-3");

        MvcResult result1 = mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000006")
                        .header("Idempotency-Key", idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper
                .readTree(result1.getResponse().getContentAsString())
                .get("appointmentId")
                .asString();

        MvcResult result2 = mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000006")
                        .header("Idempotency-Key", idempotencyKey)))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper
                .readTree(result2.getResponse().getContentAsString())
                .get("appointmentId")
                .asString();
        assert id1.equals(id2) : "Idempotent requests should return same appointment ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========

    @Test
    @DisplayName("CC-001: Prevent hard conflicts (double-booking same bay and time slot)")
    void testCreateAppointment_HardConflict() throws Exception {
        String payload1 = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-05-01T14:00:00Z", "2026-05-01T15:00:00Z", "BAY-CONFLICT");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload1)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000007")))
                .andExpect(status().isCreated());

        // Different customer+vehicle booking overlapping slot in same bay
        String payload2 = createAppointmentPayload(
                CUSTOMER_2, VEHICLE_2, LOCATION, "2026-05-01T14:30:00Z", "2026-05-01T15:30:00Z", "BAY-CONFLICT");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload2)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000007")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("CC-002: Cancel appointment transitions status to CANCELLED")
    void testCancelAppointment_StatusTransition() throws Exception {
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-05-15T19:00:00Z", "2026-05-15T20:00:00Z", "BAY-CANCEL");
        MvcResult createResult = mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000008")))
                .andExpect(status().isCreated())
                .andReturn();

        String appointmentId = objectMapper
                .readTree(createResult.getResponse().getContentAsString())
                .get("appointmentId")
                .asString();

        String cancelPayload = "{\"cancellationReason\":\"CUSTOMER_REQUEST\",\"notes\":\"Test cancellation\"}";
        mockMvc.perform(withGatewayAuth(delete("/v1/appointments/{id}/cancel", appointmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(cancelPayload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000008")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========

    @Test
    @DisplayName("FF-001: Response includes ISO 8601 startAt and createdAt timestamps")
    void testAppointment_TimestampFieldsPresent() throws Exception {
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-06-01T15:30:00Z", "2026-06-01T16:30:00Z", "BAY-1");
        mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000009")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.startAt").exists())
                .andExpect(jsonPath("$.createdAt").exists());
    }

    @Test
    @DisplayName("FF-002: Response contains expected appointment fields with correct initial status")
    void testAppointment_ResponseFields() throws Exception {
        String payload = createAppointmentPayload(
                CUSTOMER_1, VEHICLE_1, LOCATION, "2026-06-15T21:00:00Z", "2026-06-15T22:00:00Z", "BAY-1");
        MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)
                        .header("X-Correlation-Id", "00000000-0000-0000-0000-000000000010")))
                .andExpect(status().isCreated())
                .andReturn();

        var json = objectMapper.readTree(result.getResponse().getContentAsString());
        assert json.has("appointmentId") : "response must have appointmentId";
        assert json.has("status") : "response must have status";
        assert json.has("locationId") : "response must have locationId";
        assert "SCHEDULED".equals(json.get("status").asString()) : "initial status must be SCHEDULED";
    }

    // ========== HELPERS ==========

    private String createAppointmentPayload(
            String crmCustomerId,
            String crmVehicleId,
            String locationId,
            String startAt,
            String endAt,
            String resourceId) {
        return String.format(
                "{\"crmCustomerId\":\"%s\",\"crmVehicleId\":\"%s\",\"locationId\":\"%s\","
                        + "\"startAt\":\"%s\",\"endAt\":\"%s\","
                        + "\"serviceRequestIds\":[\"%s\"],\"resourceId\":\"%s\"}",
                crmCustomerId, crmVehicleId, locationId, startAt, endAt, SERVICE_REQ, resourceId);
    }

    private String createAppointmentPayloadWithSourceType(
            String crmCustomerId,
            String crmVehicleId,
            String locationId,
            String startAt,
            String endAt,
            String resourceId,
            String sourceType,
            String sourceId) {
        return String.format(
                "{\"crmCustomerId\":\"%s\",\"crmVehicleId\":\"%s\",\"locationId\":\"%s\","
                        + "\"startAt\":\"%s\",\"endAt\":\"%s\","
                        + "\"serviceRequestIds\":[\"%s\"],\"resourceId\":\"%s\","
                        + "\"sourceType\":\"%s\",\"sourceId\":\"%s\"}",
                crmCustomerId, crmVehicleId, locationId, startAt, endAt, SERVICE_REQ, resourceId, sourceType, sourceId);
    }
}
