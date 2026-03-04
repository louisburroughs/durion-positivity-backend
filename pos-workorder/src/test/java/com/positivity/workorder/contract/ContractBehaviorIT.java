package com.positivity.workorder.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.workorder.support.BaseContractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Workorder Estimate Backend Contract Behavioral Tests")
class ContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        // ========== HAPPY PATH TESTS ==========
        @Test
        @DisplayName("CP-001: Successfully create estimate with valid totals and auto-generated estimateNumber")
        void testCreateEstimate_HappyPath() throws Exception {
                String payload = createEstimatePayload("CUST-001", "VEH-001", "LOC-001", "500.00", "50.00", "550.00");
                mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-001")))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.id").exists())
                                .andExpect(jsonPath("$.estimateNumber").exists())
                                .andExpect(jsonPath("$.estimateNumber")
                                                .value(org.hamcrest.Matchers.matchesPattern("EST-\\d{4}-\\d{4}")))
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        @Test
        @DisplayName("CP-002: Successfully retrieve estimate by ID")
        void testGetEstimate_HappyPath() throws Exception {
                String payload = createEstimatePayload("CUST-002", "VEH-002", "LOC-002", "750.00", "75.00", "825.00");
                MvcResult createResult = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-002")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String estimateId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                                .asString();
                mockMvc.perform(withAuthMvc(get("/v1/workorders/estimates/{id}", estimateId)
                                .header("X-Correlation-Id", "test-002")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.id").value(estimateId))
                                .andExpect(jsonPath("$.customerId").value(toUuidString("CUST-002")));
        }

        // ========== VALIDATION ERROR TESTS ==========
        @Test
        @DisplayName("VE-001: Reject estimate with totals mismatch (subtotal + tax != total)")
        void testCreateEstimate_TotalsMismatch() throws Exception {
                String invalidPayload = createEstimatePayload("CUST-003", "VEH-003", "LOC-003", "100.00", "10.00",
                                "99.99");
                mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-001")))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("VE-002: Reject estimate with negative subtotal")
        void testCreateEstimate_NegativeSubtotal() throws Exception {
                String invalidPayload = createEstimatePayload("CUST-004", "VEH-004", "LOC-004", "-100.00", "0.00",
                                "-100.00");
                mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidPayload)
                                .header("X-Correlation-Id", "test-ve-002")))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        @Test
        @DisplayName("VE-003: Reject estimate with invalid status")
        void testCreateEstimate_InvalidStatus() throws Exception {
                String payload = createEstimatePayload("CUST-005", "VEH-005", "LOC-005", "200.00", "20.00", "220.00");
                MvcResult createResult = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ve-003")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String estimateId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                                .asString();
                String invalidStatusPayload = "{\"status\":\"INVALID_STATUS\"}";

                mockMvc.perform(withAuthMvc(patch("/v1/workorders/estimates/{id}", estimateId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidStatusPayload)
                                .header("X-Correlation-Id", "test-ve-003")))
                                .andExpect(status().isBadRequest())
                                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }

        // ========== IDEMPOTENCY TEST ==========
        @Test
        @DisplayName("ID-001: Idempotent create with same Idempotency-Key returns same resource")
        void testCreateEstimate_Idempotent() throws Exception {
                String idempotencyKey = "idem-est-" + System.currentTimeMillis();
                String payload = createEstimatePayload("CUST-006", "VEH-006", "LOC-006", "350.00", "35.00", "385.00");

                MvcResult result1 = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey)))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id1 = objectMapper.readTree(result1.getResponse().getContentAsString()).get("id").asString();

                MvcResult result2 = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-id-001")
                                .header("Idempotency-Key", idempotencyKey)))
                                .andExpect(status().isCreated())
                                .andReturn();

                String id2 = objectMapper.readTree(result2.getResponse().getContentAsString()).get("id").asString();
                assert id1.equals(id2) : "Idempotent requests should return same estimate ID";
        }

        // ========== CONCURRENCY INVARIANT TESTS ==========
        @Test
        @DisplayName("CC-001: State machine validation prevents invalid status transitions")
        void testUpdateEstimate_InvalidStateTransition() throws Exception {
                String payload = createEstimatePayload("CUST-007", "VEH-007", "LOC-007", "600.00", "60.00", "660.00");
                MvcResult createResult = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-cc-001")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String estimateId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                                .asString();
                String invalidTransition = "{\"status\":\"EXPIRED\",\"version\":0}";

                mockMvc.perform(withAuthMvc(patch("/v1/workorders/estimates/{id}", estimateId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(invalidTransition)
                                .header("X-Correlation-Id", "test-cc-001")))
                                .andExpect(status().isConflict())
                                .andExpect(jsonPath("$.code").value("CONFLICT"));
        }

        @Test
        @DisplayName("CC-002: Declined estimate can be reversed within expiration window")
        void testUpdateEstimate_ReverseDecline() throws Exception {
                String payload = createEstimatePayload("CUST-008", "VEH-008", "LOC-008", "450.00", "45.00", "495.00");
                MvcResult createResult = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-cc-002")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String estimateId = objectMapper.readTree(createResult.getResponse().getContentAsString()).get("id")
                                .asString();

                String declinePayload = "{\"status\":\"DECLINED\"}";
                mockMvc.perform(withAuthMvc(patch("/v1/workorders/estimates/{id}", estimateId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(declinePayload)
                                .header("X-Correlation-Id", "test-cc-002")))
                                .andExpect(status().isOk());

                String reversePayload = "{\"status\":\"DRAFT\"}";
                mockMvc.perform(withAuthMvc(patch("/v1/workorders/estimates/{id}", estimateId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(reversePayload)
                                .header("X-Correlation-Id", "test-cc-002")))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status").value("DRAFT"));
        }

        // ========== FIELD FORMAT VALIDATION TESTS ==========
        @Test
        @DisplayName("FF-001: ISO 8601 UTC timestamp format for createdAt")
        void testEstimate_TimestampFormat() throws Exception {
                String payload = createEstimatePayload("CUST-009", "VEH-009", "LOC-009", "300.00", "30.00", "330.00");
                MvcResult result = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ff-001")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String createdAt = objectMapper.readTree(result.getResponse().getContentAsString()).get("createdAt")
                                .asString();
                assert createdAt.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(?:\\.\\d{1,3})?Z")
                                : "createdAt must be ISO 8601 UTC format";
        }

        @Test
        @DisplayName("FF-002: Valid estimate status enum values and estimateNumber format")
        void testEstimate_ValidEnumValuesAndFormat() throws Exception {
                String payload = createEstimatePayload("CUST-010", "VEH-010", "LOC-010", "400.00", "40.00", "440.00");
                MvcResult result = mockMvc.perform(withAuthMvc(post("/v1/workorders/estimates")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(payload)
                                .header("X-Correlation-Id", "test-ff-002")))
                                .andExpect(status().isCreated())
                                .andReturn();

                String estimateNumber = objectMapper.readTree(result.getResponse().getContentAsString())
                                .get("estimateNumber").asString();
                String status = objectMapper.readTree(result.getResponse().getContentAsString()).get("status")
                                .asString();

                assert estimateNumber.matches("EST-\\d{4}-\\d{4}") : "estimateNumber must follow EST-YYYY-#### format";
                assert status.matches("DRAFT|APPROVED|DECLINED|EXPIRED|PENDING_APPROVAL")
                                : "Status must be valid enum value";
        }

        private static String toUuidString(String name) {
                return UUID.nameUUIDFromBytes(name.getBytes()).toString();
        }

        private String createEstimatePayload(String customerId, String vehicleId, String locationId,
                        String subtotal, String taxAmount, String total) {
                // crmPartyId and crmVehicleId are required fields (CAP-094 Story #93)
                return String.format(
                                "{\"customerId\":\"%s\",\"vehicleId\":\"%s\",\"locationId\":\"%s\","
                                + "\"subtotal\":%s,\"taxAmount\":%s,\"total\":%s,"
                                + "\"crmPartyId\":\"01952f4e-0000-7000-8000-000000000001\","
                                + "\"crmVehicleId\":\"01952f4e-0000-7000-8000-000000000002\","
                                + "\"crmContactIds\":[]}",
                                toUuidString(customerId), toUuidString(vehicleId), toUuidString(locationId),
                                subtotal, taxAmount, total);
        }
}
