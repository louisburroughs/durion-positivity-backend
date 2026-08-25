package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.internal.dto.receiving.ReceivingLineResponse;
import com.positivity.inventory.internal.dto.receiving.ReceivingSessionResponse;
import com.positivity.inventory.internal.exception.SourceDocumentAlreadyReceivedException;
import com.positivity.inventory.internal.exception.SourceDocumentLinesUnavailableException;
import com.positivity.inventory.internal.exception.SourceDocumentNotFoundException;
import com.positivity.inventory.service.ReceivingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
 * 404 for not-found, 409 when the purchase-order projection has not caught up (#1492)</li>
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
    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);

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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceivingSessionResponse response = ReceivingSessionResponse.builder()
                .sessionId(sessionId)
                .sourceDocumentId("PO-123")
                .sourceDocumentType("PO")
                .status("OPEN")
                .entryMethod("MANUAL")
                .createdByUserId("contract-test-user")
                .lines(List.of(
                        ReceivingLineResponse.builder()
                                .lineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .productId("PROD-001")
                                .expectedQuantity(new BigDecimal("5"))
                                .receivedQuantity(BigDecimal.ZERO)
                                .status("EXPECTED")
                                .build(),
                        ReceivingLineResponse.builder()
                                .lineId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                                .productId("PROD-002")
                                .expectedQuantity(new BigDecimal("10"))
                                .receivedQuantity(BigDecimal.ZERO)
                                .status("EXPECTED")
                                .build()))
                .createdAt(Instant.now(TEST_CLOCK))
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
        UUID sessionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        ReceivingSessionResponse response = ReceivingSessionResponse.builder()
                .sessionId(sessionId)
                .sourceDocumentId("ASN-ABC-789")
                .sourceDocumentType("ASN")
                .status("OPEN")
                .entryMethod("SCAN")
                .createdByUserId("contract-test-user")
                .lines(List.of())
                .createdAt(Instant.now(TEST_CLOCK))
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

    // ─── AC3: POST with a PO absent from the projection → 409 ────────────────

    /**
     * Verifies that requesting a receiving session for a purchase order id absent from the
     * projection returns 409 SOURCE_DOCUMENT_LINES_UNAVAILABLE with a non-empty
     * {@code nextAction}, per issue #1492: this module cannot tell replication lag from an
     * unknown purchase order id, so it asks the caller to retry.
     *
     * Issue: #1492
     */
    @Test
    @DisplayName("AC3: POST /receiving/sessions with a PO absent from the projection returns 409 with nextAction")
    void createReceivingSession_withUnprojectedPO_returns409() throws Exception {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000999");
        when(receivingService.createReceivingSession(any(), any()))
                .thenThrow(new SourceDocumentLinesUnavailableException(
                        "Purchase order " + poId + " has not replicated its lines into pos-inventory yet"));

        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceDocumentId", poId.toString());
        body.put("entryMethod", "MANUAL");

        mockMvc.perform(withReceivingAuth(post("/v1/inventory/receiving/sessions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SOURCE_DOCUMENT_LINES_UNAVAILABLE"))
                .andExpect(jsonPath("$.nextAction").isNotEmpty());
    }

    /**
     * A source document id that cannot even parse as a purchase order identifier names no order
     * this module could ever hold, so it remains a genuine 404 (issue #1492 scopes the 409 to
     * well-formed ids absent from the projection).
     *
     * Issue: #1492
     */
    @Test
    @DisplayName("AC3: POST /receiving/sessions with a non-PO source document id returns 404")
    void createReceivingSession_withNonPoSourceDocumentId_returns404() throws Exception {
        when(receivingService.createReceivingSession(any(), any()))
                .thenThrow(new SourceDocumentNotFoundException(
                        "Source document id PO-999 is not a purchase order identifier"));

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
                .thenThrow(new SourceDocumentAlreadyReceivedException("PO-456 has already been fully received"));

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
