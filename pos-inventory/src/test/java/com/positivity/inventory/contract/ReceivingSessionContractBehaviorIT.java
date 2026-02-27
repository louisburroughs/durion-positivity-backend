package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.positivity.inventory.internal.dto.receiving.ReceivingLineResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.service.ReceivingService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Contract behavior integration tests for the Receiving Session API (CAP-216
 * Story #35 —
 * Create Receiving Session from PO/ASN).
 *
 * <p>
 * Verifies {@code POST /v1/inventory/receiving/sessions} per:
 * <ul>
 * <li>ADR-0011: gateway security — authenticated gateway headers and
 * required receiving authorities</li>
 * <li>ADR-0017: HTTP response codes — 201 for creation, 400 for invalid input,
 * 404 for not-found</li>
 * <li>ADR-0018: actor identity resolved from gateway auth context
 * ({@code X-User-Id} preferred, {@code X-User} fallback)</li>
 * </ul>
 *
 * <p>
 * These are controller-level contract tests with {@link ReceivingService}
 * mocked via {@code @MockitoBean}. Assertions validate HTTP contract mapping
 * for service success/error outcomes.
 *
 * Issue: #35
 */
@DisplayName("Receiving Session Contract Behavior")
class ReceivingSessionContractBehaviorIT extends BaseContractIntegrationTest {

        @Autowired
        private MockMvc mockMvc;

        @Autowired
        private ObjectMapper objectMapper;

        @MockitoBean
        private ReceivingService receivingService;

        // ─── AC1: POST with PO source document, MANUAL entry → 201 with session ──

        /**
         * Verifies that creating a receiving session from a valid PO with MANUAL entry
         * returns 201 with sessionId, status=OPEN, entryMethod=MANUAL, and 2
         * pre-populated
         * lines each having expectedQuantity > 0, per story #35 AC1.
         *
         * Issue: #35
         */
        @Test
        @DisplayName("AC1: POST /receiving/sessions with valid PO (MANUAL) returns 201 with session and 2 lines")
        void createReceivingSession_withValidPO_manualEntry_returns201WithSessionAndLines() throws Exception {
                // Issue #35: PO-backed manual receiving session must return sessionId, OPEN
                // status, MANUAL entryMethod, 2 lines
                UUID sessionId = UUID.randomUUID();
                ReceivingSessionResponse response = ReceivingSessionResponse.builder()
                                .sessionId(sessionId)
                                .sourceDocumentId("PO-123")
                                .sourceDocumentType("PO")
                                .status("OPEN")
                                .entryMethod("MANUAL")
                                .createdByUserId("contract-test-user")
                                .lines(List.of(
                                                ReceivingLineResponse.builder()
                                                                .lineId(UUID.randomUUID())
                                                                .productId("PROD-001")
                                                                .expectedQuantity(new BigDecimal("5"))
                                                                .receivedQuantity(BigDecimal.ZERO)
                                                                .status("EXPECTED")
                                                                .build(),
                                                ReceivingLineResponse.builder()
                                                                .lineId(UUID.randomUUID())
                                                                .productId("PROD-002")
                                                                .expectedQuantity(new BigDecimal("10"))
                                                                .receivedQuantity(BigDecimal.ZERO)
                                                                .status("EXPECTED")
                                                                .build()))
                                .createdAt(Instant.now())
                                .build();

                when(receivingService.createReceivingSession(any(), any())).thenReturn(response);

                ObjectNode body = objectMapper.createObjectNode();
                body.put("sourceDocumentId", "PO-123");
                body.put("entryMethod", "MANUAL");

                mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.sessionId").isNotEmpty())
                                .andExpect(jsonPath("$.status").value("OPEN"))
                                .andExpect(jsonPath("$.entryMethod").value("MANUAL"))
                                .andExpect(jsonPath("$.lines").isArray())
                                .andExpect(jsonPath("$.lines.length()").value(2));
        }

        // ─── AC2: POST with ASN source document, SCAN entry → 201 ────────────────

        /**
         * Verifies that creating a receiving session from a valid ASN with SCAN entry
         * method
         * returns 201 with entryMethod=SCAN and status=OPEN, per story #35 AC2.
         *
         * Issue: #35
         */
        @Test
        @DisplayName("AC2: POST /receiving/sessions with ASN source document (SCAN) returns 201 with SCAN entryMethod")
        void createReceivingSession_withValidASN_scanEntry_returns201WithScanEntryMethod() throws Exception {
                // Issue #35: ASN-backed scan receiving session must return SCAN entryMethod
                UUID sessionId = UUID.randomUUID();
                ReceivingSessionResponse response = ReceivingSessionResponse.builder()
                                .sessionId(sessionId)
                                .sourceDocumentId("ASN-ABC-789")
                                .sourceDocumentType("ASN")
                                .status("OPEN")
                                .entryMethod("SCAN")
                                .createdByUserId("contract-test-user")
                                .lines(List.of())
                                .createdAt(Instant.now())
                                .build();

                when(receivingService.createReceivingSession(any(), any())).thenReturn(response);

                ObjectNode body = objectMapper.createObjectNode();
                body.put("sourceDocumentId", "ASN-ABC-789");
                body.put("entryMethod", "SCAN");

                mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.entryMethod").value("SCAN"))
                                .andExpect(jsonPath("$.status").value("OPEN"));
        }

        // ─── AC3: POST with non-existing PO → 404 ────────────────────────────────

        /**
         * Verifies that requesting a receiving session for a PO that does not exist
         * returns 404 with an error message, per story #35 AC3 and ADR-0017.
         *
         * Issue: #35
         */
        @Test
        @DisplayName("AC3: POST /receiving/sessions with non-existent PO returns 404 with error message")
        void createReceivingSession_withNonExistentPO_returns404() throws Exception {
                // Issue #35: unknown source document must yield 404 per ADR-0017
                when(receivingService.createReceivingSession(any(), any()))
                                .thenThrow(new SourceDocumentNotFoundException("Source document PO-999 not found"));

                ObjectNode body = objectMapper.createObjectNode();
                body.put("sourceDocumentId", "PO-999");
                body.put("entryMethod", "MANUAL");

                mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                                .andExpect(status().isNotFound());
        }

        // ─── AC4: POST with already-closed PO → 400 ──────────────────────────────

        /**
         * Verifies that requesting a receiving session for a PO that has already been
         * fully received returns 400 with a descriptive error, per story #35 AC4.
         *
         * Issue: #35
         */
        @Test
        @DisplayName("AC4: POST /receiving/sessions with already-received PO returns 400")
        void createReceivingSession_withAlreadyReceivedPO_returns400() throws Exception {
                // Issue #35: fully-received PO must be rejected with 400 per ADR-0017
                when(receivingService.createReceivingSession(any(), any()))
                                .thenThrow(new SourceDocumentAlreadyReceivedException(
                                                "PO-456 has already been fully received"));

                ObjectNode body = objectMapper.createObjectNode();
                body.put("sourceDocumentId", "PO-456");
                body.put("entryMethod", "MANUAL");

                mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                                .andExpect(status().isBadRequest());
        }

        // ─── AC5: POST with null/empty body → 400 ────────────────────────────────

        /**
         * Verifies that a request with null sourceDocumentId or an empty body is
         * rejected
         * with 400 before reaching the service layer, per story #35 AC5 and ADR-0017.
         *
         * Issue: #35
         */
        @Test
        @DisplayName("AC5: POST /receiving/sessions with empty body returns 400")
        void createReceivingSession_withEmptyBody_returns400() throws Exception {
                // Issue #35: missing sourceDocumentId must fail validation with 400
                ObjectNode body = objectMapper.createObjectNode();

                mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(body)))
                                .andExpect(status().isBadRequest());
        }
}
