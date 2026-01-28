package com.positivity.security;

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
@DisplayName("Security Policy Backend Contract Behavioral Tests")
class ContractBehaviorIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ========== HAPPY PATH TESTS ==========
    @Test
    @DisplayName("CP-001: Successfully create security policy with valid permissions")
    void testCreateSecurityPolicy_HappyPath() throws Exception {
        String payload = createPolicyPayload("policy-001", "read,write", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.policyId").value("policy-001"))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("CP-002: Successfully retrieve security policy by ID")
    void testGetSecurityPolicy_HappyPath() throws Exception {
        String payload = createPolicyPayload("policy-002", "read,write,delete", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        mockMvc.perform(get("/api/v1/policies/{id}", policyId)
                .header("X-Correlation-Id", "test-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(policyId))
                .andExpect(jsonPath("$.policyId").value("policy-002"));
    }

    // ========== VALIDATION ERROR TESTS ==========
    @Test
    @DisplayName("VE-001: Reject policy with empty permissions list")
    void testCreateSecurityPolicy_EmptyPermissions() throws Exception {
        String invalidPayload = createPolicyPayload("policy-003", "", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-002: Reject policy with invalid permission values")
    void testCreateSecurityPolicy_InvalidPermission() throws Exception {
        String invalidPayload = createPolicyPayload("policy-004", "read,invalid-perm", "ACTIVE");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-002"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("VE-003: Reject policy with invalid status")
    void testCreateSecurityPolicy_InvalidStatus() throws Exception {
        String invalidPayload = createPolicyPayload("policy-005", "read,write", "UNKNOWN_STATUS");
        mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(invalidPayload)
                .header("X-Correlation-Id", "test-ve-003"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ========== IDEMPOTENCY TEST ==========
    @Test
    @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
    void testCreateSecurityPolicy_Idempotent() throws Exception {
        String idempotencyKey = "idem-policy-" + System.currentTimeMillis();
        String payload = createPolicyPayload("policy-006", "read,write,execute", "ACTIVE");

        MvcResult result1 = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asText();

        MvcResult result2 = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-id-001")
                .header("Idempotency-Key", idempotencyKey))
                .andExpect(status().isCreated())
                .andReturn();

        String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asText();
        assert id1.equals(id2) : "Idempotent requests should return same policy ID";
    }

    // ========== CONCURRENCY INVARIANT TESTS ==========
    @Test
    @DisplayName("CC-001: Optimistic locking prevents concurrent updates with stale version")
    void testUpdateSecurityPolicy_OptimisticLockingConflict() throws Exception {
        String payload = createPolicyPayload("policy-007", "read", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-001"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"permissions\":\"read,write\",\"version\":0}";

        mockMvc.perform(patch("/api/v1/policies/{id}", policyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-001")
                .header("If-Match", "0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
    }

    @Test
    @DisplayName("CC-002: Status transition respects active policy constraints")
    void testUpdateSecurityPolicy_StatusTransition() throws Exception {
        String payload = createPolicyPayload("policy-008", "read,write,delete", "ACTIVE");
        MvcResult createResult = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String policyId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id").asText();
        String updatePayload = "{\"status\":\"INACTIVE\"}";

        mockMvc.perform(patch("/api/v1/policies/{id}/status", policyId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updatePayload)
                .header("X-Correlation-Id", "test-cc-002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"));
    }

    // ========== FIELD FORMAT VALIDATION TESTS ==========
    @Test
    @DisplayName("FF-001: ISO 8601 timestamp format for createdAt")
    void testSecurityPolicy_TimestampFormat() throws Exception {
        String payload = createPolicyPayload("policy-009", "read,write", "ACTIVE");
        MvcResult result = mockMvc.perform(post("/api/v1/policies")
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
    @DisplayName("FF-002: Valid permission and status enum values")
    void testSecurityPolicy_ValidEnumValues() throws Exception {
        String[] validPermissions = { "read", "write", "delete", "execute" };
        String payload = createPolicyPayload("policy-010", "read,write,delete,execute", "ACTIVE");
        MvcResult result = mockMvc.perform(post("/api/v1/policies")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Correlation-Id", "test-ff-002"))
                .andExpect(status().isCreated())
                .andReturn();

        String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status").asText();
        assert status.matches("ACTIVE|INACTIVE|ARCHIVED") : "Policy status must be valid enum value";
    }

    private String createPolicyPayload(String policyId, String permissions, String status) {
        return String.format(
                "{\"policyId\":\"%s\",\"permissions\":\"%s\",\"status\":\"%s\"}",
                policyId, permissions, status);
    }
}
