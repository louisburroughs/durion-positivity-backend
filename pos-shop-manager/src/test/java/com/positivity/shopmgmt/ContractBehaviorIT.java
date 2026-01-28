package com.positivity.shopmgmt;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Shop Manager Appointment Backend Contract Behavioral Tests")
class ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== HAPPY PATH TESTS ==========
    @Test
    @DisplayName("CP-001: Successfully create appointment for workorder with valid facility timezone")
    void testCreateAppointment_HappyPath() throws Exception {
        String payload = createAppointmentPayload("WO-001", "WORKORDER", "LOC-001",
                "2026-01-27T14:30:00-05:00", "2026-01-27T15:30:00-05:00", "BAY");
        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sourceId").value("WO-001"))
                .andExpect(jsonPath("$.status").value("SCHEDULED"));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve appointment by ID")
    void testGetAppointment_HappyPath() throws Exception {
        String payload = createAppointmentPayload("EST-002", "ESTIMATE", "LOC-002",
                "2026-02-15T10:00:00-05:00", "2026-02-15T11:30:00-05:00", "MOBILE_UNIT");
        MvcResult createResult = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String appointmentId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                .asText();
        mockMvc.perform(get("/api/v1/appointments/{id}", appointmentId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(appointmentId))
                .andExpect(jsonPath("$.sourceType").value("ESTIMATE"));
    }

    // ========== VALIDATION ERROR TESTS ==========
    @Test
    @DisplayName("VE-001: Reject appointment with invalid sourceType")
    void testCreateAppointment_InvalidSourceType() throws Exception {
        String invalidPayload = createAppointmentPayload("INV-001", "INVALID_TYPE", "LOC-003",
                "2026-03-01T09:00:00-05:00", "2026-03-01T10:00:00-05:00", "BAY");
        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject appointment with end time before start time")
    void testCreateAppointment_InvalidTimeRange() throws Exception {
        String invalidPayload = createAppointmentPayload("WO-003", "WORKORDER", "LOC-004",
                "2026-03-15T15:00:00-05:00", "2026-03-15T14:00:00-05:00", "BAY");
        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-003: Reject appointment with invalid assignmentType")
    void testCreateAppointment_InvalidAssignmentType() throws Exception {
        String invalidPayload = createAppointmentPayload("EST-004", "ESTIMATE", "LOC-005",
                "2026-04-01T13:00:00-05:00", "2026-04-01T14:00:00-05:00", "INVALID_ASSIGNMENT");
        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ========== IDEMPOTENCY TEST ==========
    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateAppointment_Idempotent() throws Exception {
        String idempotencyKey = "idem-appt-" + System.currentTimeMillis();
        String payload = createAppointmentPayload("WO-005", "WORKORDER", "LOC-006",
                "2026-04-20T11:00:00-05:00", "2026-04-20T12:30:00-05:00", "BAY");

        MvcResult result1 = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same appointment ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========
    @Test
    @DisplayName("CC-001: Prevent hard conflicts (double-booking same facility bay)")
    void testCreateAppointment_HardConflict() throws Exception {
        String payload1 = createAppointmentPayload("WO-006", "WORKORDER", "LOC-007",
                "2026-05-01T09:00:00-05:00", "2026-05-01T10:00:00-05:00", "BAY");

        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload1)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated());

        String payload2 = createAppointmentPayload("WO-007", "WORKORDER", "LOC-007",
                "2026-05-01T09:30:00-05:00", "2026-05-01T10:30:00-05:00", "BAY");

        mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload2)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Appointment status state machine validation")
    void testUpdateAppointment_StateTransition() throws Exception {
        String payload = createAppointmentPayload("WO-008", "WORKORDER", "LOC-008",
                "2026-05-15T14:00:00-05:00", "2026-05-15T15:00:00-05:00", "MOBILE_UNIT");
        MvcResult createResult = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String appointmentId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                .asText();
        String confirmPayload = "{\"status\":\"CONFIRMED\"}";

        mockMvc.perform(patch("/api/v1/appointments/{id}/status", appointmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(confirmPayload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========
    @Test
    @DisplayName("FF-001: ISO 8601 datetime with facility timezone offset validation")
    void testAppointment_TimestampFormatWithTimezone() throws Exception {
        String payload = createAppointmentPayload("EST-009", "ESTIMATE", "LOC-009",
                "2026-06-01T10:30:00-05:00", "2026-06-01T11:30:00-05:00", "BAY");
        MvcResult result = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String scheduledStart = objectMapper.readTree(result.getResponse().getContentAsString())
                .get("scheduledStartDateTime").asText();
        assert scheduledStart.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}[+-]\\d{2}:\\d{2}")
                : "scheduledStartDateTime must be ISO 8601 with timezone offset";
    }

    @Test
    @DisplayName("FF-002: Valid enum values for sourceType, assignmentType, and status")
    void testAppointment_ValidEnumValues() throws Exception {
        String payload = createAppointmentPayload("WO-010", "WORKORDER", "LOC-010",
                "2026-06-15T16:00:00-05:00", "2026-06-15T17:00:00-05:00", "BAY");
        MvcResult result = mockMvc.perform(post("/api/v1/appointments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String sourceType = objectMapper.readTree(result.getResponse().getContentAsString()).get("sourceType").asText();
        String assignmentType = objectMapper.readTree(result.getResponse().getContentAsString()).get("assignmentType")
                .asText();
        String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();

        assert sourceType.matches("WORKORDER|ESTIMATE") : "sourceType must be valid enum";
        assert assignmentType.matches("BAY|MOBILE_UNIT") : "assignmentType must be valid enum";
        assert status.matches("SCHEDULED|CONFIRMED|IN_PROGRESS|CANCELLED|COMPLETED") : "status must be valid enum";
    }

    private String createAppointmentPayload(String sourceId, String sourceType, String facilityId,
            String scheduledStartDateTime, String scheduledEndDateTime, String assignmentType) {
        return String.format(
                "{\"sourceId\":\"%s\",\"sourceType\":\"%s\",\"facilityId\":\"%s\",\"scheduledStartDateTime\":\"%s\",\"scheduledEndDateTime\":\"%s\",\"assignmentType\":\"%s\"}",
                sourceId, sourceType, facilityId, scheduledStartDateTime, scheduledEndDateTime, assignmentType);
    }
}
