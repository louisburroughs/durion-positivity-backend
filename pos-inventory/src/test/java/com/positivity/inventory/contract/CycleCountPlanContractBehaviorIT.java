package com.positivity.inventory.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.positivity.inventory.internal.repository.CycleCountPlanRepository;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for CycleCountPlanController.
 *
 * <p>
 * Verifies plan creation and retrieval lifecycle per ADR-0017 HTTP response
 * code standards and ADR-0018 audit actor-from-security-context requirement.
 *
 * Issue: #176
 */
@DisplayName("Cycle Count Plan Contract Behavior")
class CycleCountPlanContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @Autowired
        private CycleCountPlanRepository cycleCountPlanRepository;

        @BeforeEach
        void setUp() {
                // Issue #176: ensure a clean slate before each test
                cycleCountPlanRepository.deleteAll();
        }

        // ─── AC-176-1: Create plan with valid request ──────────────────────────────

        /**
         * Verifies that a valid cycle count plan creation returns 201 with planId
         * and status PLANNED per story #176 acceptance criteria.
         */
        @Test
        @DisplayName("AC-176-1: create cycle count plan with valid future scheduledDate returns 201 with PLANNED status")
        void createCycleCountPlan_validRequest_returns201() throws Exception {
                String body = buildCreatePlanRequestBody(
                                UUID.randomUUID(),
                                List.of(UUID.randomUUID(), UUID.randomUUID()),
                                "Q1 Zone A Audit",
                                LocalDate.now().plusDays(7));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountPlans"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.planId").exists())
                                .andExpect(jsonPath("$.status").value("PLANNED"));
        }

        // ─── AC-176-2: Past scheduledDate returns 400 ──────────────────────────────

        /**
         * Verifies that a cycle count plan with a scheduledDate in the past
         * returns 400 per ADR-0017 validation error handling.
         */
        @Test
        @DisplayName("AC-176-2: create plan with past scheduledDate returns 400")
        void createCycleCountPlan_pastScheduledDate_returns400() throws Exception {
                String body = buildCreatePlanRequestBody(
                                UUID.randomUUID(),
                                List.of(UUID.randomUUID()),
                                "Past date plan",
                                LocalDate.now().minusDays(1));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountPlans"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC-176-3: Empty zoneIds returns 400 ──────────────────────────────────

        /**
         * Verifies that a cycle count plan with an empty zoneIds list returns 400.
         */
        @Test
        @DisplayName("AC-176-3: create plan with empty zoneIds returns 400")
        void createCycleCountPlan_emptyZoneIds_returns400() throws Exception {
                // Issue #176: zoneIds=[] → IAE → 400
                String body = objectMapper.writeValueAsString(
                                objectMapper.createObjectNode()
                                                .put("locationId", UUID.randomUUID().toString())
                                                .put("scheduledDate", LocalDate.now().plusDays(7).toString())
                                                .set("zoneIds", objectMapper.createArrayNode()));

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountPlans"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC-176-4: Null locationId returns 400 ────────────────────────────────

        /**
         * Verifies that a cycle count plan request without locationId returns 400.
         */
        @Test
        @DisplayName("AC-176-4: create plan with null locationId returns 400")
        void createCycleCountPlan_missingLocationId_returns400() throws Exception {
                // Issue #176: locationId=null → 400
                var node = objectMapper.createObjectNode();
                node.putNull("locationId");
                node.put("scheduledDate", LocalDate.now().plusDays(7).toString());
                var zoneArray = objectMapper.createArrayNode();
                zoneArray.add(UUID.randomUUID().toString());
                node.set("zoneIds", zoneArray);

                mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountPlans"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(node)))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC-176-5: Get existing plan returns 200 ──────────────────────────────

        /**
         * Verifies that retrieving a created plan by planId returns 200 with the
         * correct planId in the response body.
         */
        @Test
        @DisplayName("AC-176-5: get existing cycle count plan returns 200 with matching planId")
        void getCycleCountPlan_existingPlan_returns200() throws Exception {
                UUID planId = createPlan("Q2 Zone B Audit", LocalDate.now().plusDays(3));

                mockMvc.perform(withGatewayAuth(
                                get("/v1/inventory/cycleCountPlans/{planId}", planId)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.planId").value(planId.toString()));
        }

        // ─── AC-176-6: Get non-existent plan returns 404 ──────────────────────────

        /**
         * Verifies that retrieving a non-existent plan returns 404 per ADR-0017.
         */
        @Test
        @DisplayName("AC-176-6: get non-existent cycle count plan returns 404")
        void getCycleCountPlan_nonExistentPlan_returns404() throws Exception {
                mockMvc.perform(withGatewayAuth(
                                get("/v1/inventory/cycleCountPlans/{planId}", UUID.randomUUID())))
                                .andExpect(status().isNotFound());
        }

        // ─── Helpers ──────────────────────────────────────────────────────────────

        /**
         * Builds a JSON request body for creating a cycle count plan.
         *
         * @param locationId    location UUID
         * @param zoneIds       list of zone UUIDs
         * @param planName      optional plan name
         * @param scheduledDate the scheduled date for the count
         * @return serialized JSON request body
         */
        private String buildCreatePlanRequestBody(
                        UUID locationId,
                        List<UUID> zoneIds,
                        String planName,
                        LocalDate scheduledDate) throws Exception {
                var node = objectMapper.createObjectNode();
                node.put("locationId", locationId.toString());
                node.put("planName", planName);
                node.put("scheduledDate", scheduledDate.toString());
                var zoneArray = objectMapper.createArrayNode();
                zoneIds.forEach(id -> zoneArray.add(id.toString()));
                node.set("zoneIds", zoneArray);
                return objectMapper.writeValueAsString(node);
        }

        /**
         * Creates a cycle count plan via the API and returns its planId.
         *
         * @param planName      plan name
         * @param scheduledDate scheduled date (must be future)
         * @return the created plan UUID
         *         Issue: #176
         */
        private UUID createPlan(String planName, LocalDate scheduledDate) throws Exception {
                String body = buildCreatePlanRequestBody(
                                UUID.randomUUID(),
                                List.of(UUID.randomUUID()),
                                planName,
                                scheduledDate);

                MvcResult result = mockMvc.perform(withGatewayAuth(post("/v1/inventory/cycleCountPlans"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                                .andExpect(status().isCreated())
                                .andReturn();

                JsonNode response = objectMapper.readTree(result.getResponse().getContentAsString());
                return UUID.fromString(response.path("planId").asString());
        }
}
