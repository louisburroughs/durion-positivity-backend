package com.positivity.people;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("People Management Backend Contract Behavioral Tests")
class ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== HAPPY PATH TESTS ==========
    @Test
    @DisplayName("CP-001: Successfully create employee with valid contact details")
    void testCreateEmployee_HappyPath() throws Exception {
        String payload = createEmployeePayload("John", "Doe", "john.doe@example.com", "johndoe", "555-0001");
        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.firstName").value("John"))
                .andExpect(jsonPath("$.lastName").value("Doe"));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve employee by ID")
    void testGetEmployee_HappyPath() throws Exception {
        String payload = createEmployeePayload("Jane", "Smith", "jane.smith@example.com", "janesmith", "555-0002");
        MvcResult createResult = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String employeeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/v1/employees/{id}", employeeId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(employeeId))
                .andExpect(jsonPath("$.primaryEmail").value("jane.smith@example.com"));
    }

    // ========== VALIDATION ERROR TESTS ==========
    @Test
    @DisplayName("VE-001: Reject employee with invalid email format")
    void testCreateEmployee_InvalidEmailFormat() throws Exception {
        String invalidPayload = createEmployeePayload("Bob", "Johnson", "invalid-email", "bjohnson", "555-0003");
        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject employee with duplicate email")
    void testCreateEmployee_DuplicateEmail() throws Exception {
        String email = "duplicate@example.com";
        String payload = createEmployeePayload("Alice", "Brown", email, "abrown1", "555-0004");

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createEmployeePayload("Alex", "Brown", email, "abrown2", "555-0005"))
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("VE-003: Reject employee with duplicate username")
    void testCreateEmployee_DuplicateUsername() throws Exception {
        String username = "uniqueuser";
        String payload = createEmployeePayload("Chris", "Davis", "chris.davis@example.com", username, "555-0006");

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(createEmployeePayload("Charlie", "Davis", "charlie.davis@example.com", username, "555-0007"))
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    // ========== IDEMPOTENCY TEST ==========
    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateEmployee_Idempotent() throws Exception {
        String idempotencyKey = "idem-emp-" + System.currentTimeMillis();
        String payload = createEmployeePayload("Diana", "Evans", "diana.evans@example.com", "devans", "555-0008");

        MvcResult result1 = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same employee ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========
    @Test
    @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
    void testUpdateEmployee_OptimisticLockingConflict() throws Exception {
        String payload = createEmployeePayload("Edward", "Frank", "edward.frank@example.com", "efrank", "555-0009");
        MvcResult createResult = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String employeeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"firstName\":\"Edward\",\"lastName\":\"Franklin\",\"version\":0}";

        mockMvc.perform(patch("/api/v1/employees/{id}", employeeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-001")
                .header("If-Match", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Employee status transitions respect business rules")
    void testUpdateEmployee_ValidStatusTransition() throws Exception {
        String payload = createEmployeePayload("Fiona", "Green", "fiona.green@example.com", "fgreen", "555-0010");
        MvcResult createResult = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String employeeId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"status\":\"ON_LEAVE\"}";

        mockMvc.perform(patch("/api/v1/employees/{id}/status", employeeId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ON_LEAVE"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========
    @Test
    @DisplayName("FF-001: ISO 8601 timestamp format for createdAt")
    void testEmployee_TimestampFormat() throws Exception {
        String payload = createEmployeePayload("George", "Harris", "george.harris@example.com", "gharris", "555-0011");
        MvcResult result = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String createdAt = objectMapper.readTree(result.getResponse().getContentAsString()).get("createdAt").asText();
        assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                : "createdAt must be ISO 8601 UTC format";
    }

    @Test
    @DisplayName("FF-002: Valid employee status enum values")
    void testEmployee_ValidStatusEnums() throws Exception {
        String payload = createEmployeePayload("Hannah", "Irving", "hannah.irving@example.com", "hirving", "555-0012");
        MvcResult result = mockMvc.perform(post("/api/v1/employees")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
        assert status.matches("ACTIVE|INACTIVE|ON_LEAVE|TERMINATED") : "Employee status must be valid enum value";
    }

    private String createEmployeePayload(String firstName, String lastName, String email, String username,
            String phone) {
        return String.format(
                "{\"firstName\":\"%s\",\"lastName\":\"%s\",\"primaryEmail\":\"%s\",\"username\":\"%s\",\"phoneNumber\":\"%s\"}",
                firstName, lastName, email, username, phone);
    }
}
