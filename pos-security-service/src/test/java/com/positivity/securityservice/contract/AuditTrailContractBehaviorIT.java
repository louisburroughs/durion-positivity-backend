package com.positivity.securityservice.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.securityservice.BaseContractIntegrationTest;
import com.positivity.securityservice.PosSecurityServiceApplication;
import com.positivity.securityservice.internal.service.TokenRevocationManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Contract behavior tests for immutable audit trail endpoints.
 *
 * Issue: #41
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = PosSecurityServiceApplication.class,
        properties = {"security.jwt.secret=test-jwt-secret-key-01234567890123456789"})
@ActiveProfiles("test")
@WithMockUser(
        username = "system-admin",
        roles = {"ADMIN"})
@DisplayName("Issue #41 Immutable Audit Trail Contract Behavior")
class AuditTrailContractBehaviorIT extends BaseContractIntegrationTest {

    @MockitoBean
    private TokenRevocationManager tokenRevocationManager;

    private MockHttpServletRequestBuilder withAuditAdminAuth(MockHttpServletRequestBuilder builder) {
        return withAuth(builder, "ROLE_ADMIN,security:audit:create,security:audit:view");
    }

    // Issue #41: AC1 validates audit event append-only create endpoint contract.
    @Test
    @DisplayName("AC1: create audit event returns 201 with eventId and timestamp")
    void ac1_createAuditEvent_returnsCreatedWithIdentifiers() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventType", "PRICE_BOOK_UPDATE",
                "actorId", "user-123",
                "entityId", "PRODUCT-123",
                "entityType", "Product",
                "oldValue", Map.of("price", "10.00"),
                "newValue", Map.of("price", "12.50")));

        mockMvc.perform(withAuditAdminAuth(post("/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.eventId").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    // Issue #41: AC2 validates DELETE immutability enforcement with 405.
    @Test
    @DisplayName("AC2: deleting audit event is method-not-allowed")
    void ac2_deleteAuditEvent_returnsMethodNotAllowed() throws Exception {
        String eventId = createAuditEventAndReturnId("PRODUCT-DELETE-1");

        mockMvc.perform(withAuditAdminAuth(delete("/v1/audit/events/{eventId}", eventId)))
                .andExpect(status().isMethodNotAllowed());
    }

    // Issue #41: AC3 validates PUT immutability enforcement with 405.
    @Test
    @DisplayName("AC3: updating audit event is method-not-allowed")
    void ac3_updateAuditEvent_returnsMethodNotAllowed() throws Exception {
        String eventId = createAuditEventAndReturnId("PRODUCT-PUT-1");

        String updatePayload = objectMapper.writeValueAsString(Map.of(
                "eventType", "PRICE_BOOK_UPDATE",
                "actorId", "user-456",
                "entityId", "PRODUCT-PUT-1",
                "entityType", "Product",
                "oldValue", Map.of("price", "12.50"),
                "newValue", Map.of("price", "13.00")));

        mockMvc.perform(withAuditAdminAuth(put("/v1/audit/events/{eventId}", eventId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload)))
                .andExpect(status().isMethodNotAllowed());
    }

    // Issue #41: AC4 validates pricing snapshot + rule trace creation contract.
    @Test
    @DisplayName("AC4: create pricing snapshot returns 201 with snapshotId")
    void ac4_createPricingSnapshot_returnsCreatedWithSnapshotId() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "quoteContext",
                Map.of("productIds", List.of("SKU-1"), "quantity", 2),
                "finalPrice",
                "25.00",
                "evaluationSteps",
                List.of(
                        Map.of("ruleId", "R1", "status", "APPLIED", "inputs", Map.of(), "outputs", Map.of()),
                        Map.of("ruleId", "R2", "status", "REJECTED", "inputs", Map.of(), "outputs", Map.of()))));

        mockMvc.perform(withAuditAdminAuth(post("/v1/audit/pricing-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.snapshotId").isNotEmpty());
    }

    // Issue #41: AC5 validates retrieval of pricing snapshot including evaluation
    // steps.
    @Test
    @DisplayName("AC5: get pricing snapshot returns snapshot with evaluation steps")
    void ac5_getPricingSnapshot_returnsTraceDetails() throws Exception {
        String snapshotId = createPricingSnapshotAndReturnId();

        mockMvc.perform(withAuditAdminAuth(get("/v1/audit/pricing-snapshots/{snapshotId}", snapshotId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.snapshotId").value(snapshotId))
                .andExpect(jsonPath("$.evaluationSteps").isArray())
                .andExpect(jsonPath("$.evaluationSteps.length()").value(2));
    }

    // Issue #41: AC6 validates search endpoint filtering by entity id/type.
    @Test
    @DisplayName("AC6: search audit events by entity returns matching entries")
    void ac6_searchAuditEvents_filtersByEntity() throws Exception {
        createAuditEventAndReturnId("PRODUCT-456");

        String responseBody = mockMvc.perform(withAuditAdminAuth(
                        get("/v1/audit/events").param("entityId", "PRODUCT-456").param("entityType", "Product")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Response is Page<AuditLogEventDto>; deserialize as Map and extract content
        // list
        @SuppressWarnings("unchecked")
        Map<String, Object> pageResponse = objectMapper.readValue(responseBody, Map.class);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> events = (List<Map<String, Object>>) pageResponse.get("content");
        assertThat(events)
                .as("Expected at least one audit event for PRODUCT-456")
                .isNotEmpty()
                .anySatisfy(event -> {
                    assertThat(event).containsEntry("entityId", "PRODUCT-456").containsEntry("entityType", "Product");
                });
    }

    private String createAuditEventAndReturnId(String entityId) throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",
                "PRICE_BOOK_UPDATE",
                "actorId",
                "user-123",
                "entityId",
                entityId,
                "entityType",
                "Product",
                "oldValue",
                Map.of("price", "10.00"),
                "newValue",
                Map.of("price", "12.50")));

        MvcResult result = mockMvc.perform(withAuditAdminAuth(post("/v1/audit/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(response.get("eventId"));
    }

    private String createPricingSnapshotAndReturnId() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "quoteContext",
                Map.of("productIds", List.of("SKU-1"), "quantity", 2),
                "finalPrice",
                "25.00",
                "evaluationSteps",
                List.of(
                        Map.of("ruleId", "R1", "status", "APPLIED", "inputs", Map.of(), "outputs", Map.of()),
                        Map.of("ruleId", "R2", "status", "REJECTED", "inputs", Map.of(), "outputs", Map.of()))));

        MvcResult result = mockMvc.perform(withAuditAdminAuth(post("/v1/audit/pricing-snapshots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload)))
                .andExpect(status().isCreated())
                .andReturn();

        @SuppressWarnings("unchecked")
        Map<String, Object> response =
                objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return String.valueOf(response.get("snapshotId"));
    }
}
