package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.dto.receiving.ReceiveItemsResponse;
import com.positivity.inventory.service.ReceivingService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract behavior integration tests for the Receive Items into Staging API
 * (CAP-216 Story #34 — Receive Items into Staging).
 *
 * <p>
 * Verifies {@code POST /v1/inventory/receiving/sessions/{sessionId}/receive}
 * per:
 * <ul>
 * <li>ADR-0011: gateway security — authenticated gateway headers and
 * {@code inventory:receiving:complete} authority required</li>
 * <li>ADR-0017: HTTP response contract — 200 OK for successful receipt paths
 * covered in this suite</li>
 * <li>ADR-0018: actor identity resolved from gateway auth context
 * ({@code X-User-Id} preferred, {@code X-User} fallback)</li>
 * </ul>
 *
 * Issue: #34
 */
@DisplayName("Receive Items into Staging Contract Behavior")
class ReceivingItemsContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ReceivingService receivingService;

        // ─── AC1: Exact receipt — no variances ────────────────────────────────────

        /**
         * Verifies that receiving the exact expected quantity returns 200 OK with an
         * empty variances array and linesProcessed=1, per story #34 AC1.
         *
         * <p>
         * ADR-0011: {@code inventory:receiving:complete} authority supplied via gateway
         * header.
         * ADR-0017: successful receive action returns 200 OK.
         * ADR-0018: actor identity resolved from gateway auth context
         * ({@code X-User-Id} preferred, {@code X-User} fallback).
         *
         * Issue: #34
         */
        @Test
        @DisplayName("AC1: POST /sessions/{sessionId}/receive with exact expected quantity returns 200 with no variances")
        void receiveItems_exactExpectedQuantity_returns200WithNoVariances() throws Exception {
                // Issue #34: exact receipt (receivedQty == expectedQty) must return 200, empty
                // variances, linesProcessed=1
                UUID sessionId = UUID.randomUUID();
                UUID lineId = UUID.randomUUID();

                ReceiveItemsResponse response = ReceiveItemsResponse.builder()
                                .sessionId(sessionId)
                                .sessionStatus("COMPLETED")
                                .linesProcessed(1)
                                .variances(List.of())
                                .build();

                when(receivingService.receiveItemsIntoStaging(eq(sessionId), any(), any()))
                                .thenReturn(response);

                ObjectNode requestBody = objectMapper.createObjectNode();
                ArrayNode linesArray = objectMapper.createArrayNode();
                ObjectNode lineNode = objectMapper.createObjectNode();
                lineNode.put("lineId", lineId.toString());
                lineNode.put("receivedQuantity", 10);
                linesArray.add(lineNode);
                requestBody.set("lines", linesArray);

                mockMvc.perform(withReceivingAuth(
                                post("/v1/inventory/receiving/sessions/{sessionId}/receive", sessionId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.variances").isArray())
                                .andExpect(jsonPath("$.variances.length()").value(0))
                                .andExpect(jsonPath("$.linesProcessed").value(1));
        }

        // ─── AC2: Short receipt — SHORTAGE variance ────────────────────────────────

        /**
         * Verifies that receiving less than the expected quantity returns 200 OK with a
         * SHORTAGE variance entry indicating the deficit, per story #34 AC2.
         *
         * <p>
         * ADR-0017: partial receipt still returns 200 OK (not an error condition).
         *
         * Issue: #34
         */
        @Test
        @DisplayName("AC2: POST /sessions/{sessionId}/receive with short quantity returns 200 with SHORTAGE variance")
        void receiveItems_shortOfExpectedQuantity_returns200WithShortageVariance() throws Exception {
                // Issue #34: short receipt (receivedQty < expectedQty) must return 200 with
                // SHORTAGE variance, varianceQty=2
                UUID sessionId = UUID.randomUUID();
                UUID lineId = UUID.randomUUID();

                ReceiveItemsResponse response = ReceiveItemsResponse.builder()
                                .sessionId(sessionId)
                                .sessionStatus("IN_PROGRESS")
                                .linesProcessed(1)
                                .variances(List.of(
                                                ReceiveItemsResponse.VarianceSummaryResponse.builder()
                                                                .lineId(lineId)
                                                                .productId("PROD-001")
                                                                .varianceType("SHORTAGE")
                                                                .varianceQuantity(new BigDecimal("2"))
                                                                .expectedQuantity(new BigDecimal("10"))
                                                                .receivedQuantity(new BigDecimal("8"))
                                                                .build()))
                                .build();

                when(receivingService.receiveItemsIntoStaging(eq(sessionId), any(), any()))
                                .thenReturn(response);

                ObjectNode requestBody = objectMapper.createObjectNode();
                ArrayNode linesArray = objectMapper.createArrayNode();
                ObjectNode lineNode = objectMapper.createObjectNode();
                lineNode.put("lineId", lineId.toString());
                lineNode.put("receivedQuantity", 8);
                linesArray.add(lineNode);
                requestBody.set("lines", linesArray);

                mockMvc.perform(withReceivingAuth(
                                post("/v1/inventory/receiving/sessions/{sessionId}/receive", sessionId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.variances[0].varianceType").value("SHORTAGE"))
                                .andExpect(jsonPath("$.variances[0].varianceQuantity").value(2));
        }

        // ─── AC3: Over receipt — OVERAGE variance ─────────────────────────────────

        /**
         * Verifies that receiving more than the expected quantity returns 200 OK with
         * an
         * OVERAGE variance entry indicating the surplus, per story #34 AC3.
         *
         * <p>
         * ADR-0017: overage receipt still returns 200 OK (not an error condition).
         *
         * Issue: #34
         */
        @Test
        @DisplayName("AC3: POST /sessions/{sessionId}/receive with excess quantity returns 200 with OVERAGE variance")
        void receiveItems_overExpectedQuantity_returns200WithOverageVariance() throws Exception {
                // Issue #34: over receipt (receivedQty > expectedQty) must return 200 with
                // OVERAGE variance, varianceQty=2
                UUID sessionId = UUID.randomUUID();
                UUID lineId = UUID.randomUUID();

                ReceiveItemsResponse response = ReceiveItemsResponse.builder()
                                .sessionId(sessionId)
                                .sessionStatus("IN_PROGRESS")
                                .linesProcessed(1)
                                .variances(List.of(
                                                ReceiveItemsResponse.VarianceSummaryResponse.builder()
                                                                .lineId(lineId)
                                                                .productId("PROD-001")
                                                                .varianceType("OVERAGE")
                                                                .varianceQuantity(new BigDecimal("2"))
                                                                .expectedQuantity(new BigDecimal("10"))
                                                                .receivedQuantity(new BigDecimal("12"))
                                                                .build()))
                                .build();

                when(receivingService.receiveItemsIntoStaging(eq(sessionId), any(), any()))
                                .thenReturn(response);

                ObjectNode requestBody = objectMapper.createObjectNode();
                ArrayNode linesArray = objectMapper.createArrayNode();
                ObjectNode lineNode = objectMapper.createObjectNode();
                lineNode.put("lineId", lineId.toString());
                lineNode.put("receivedQuantity", 12);
                linesArray.add(lineNode);
                requestBody.set("lines", linesArray);

                mockMvc.perform(withReceivingAuth(
                                post("/v1/inventory/receiving/sessions/{sessionId}/receive", sessionId))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(requestBody)))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.variances[0].varianceType").value("OVERAGE"))
                                .andExpect(jsonPath("$.variances[0].varianceQuantity").value(2));
        }
}
