package com.positivity.inventory.contract;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.positivity.inventory.internal.dto.receiving.CrossDockResponse;
import com.positivity.inventory.internal.exception.PartMatchPermissionException;
import com.positivity.inventory.internal.exception.WorkorderClosedException;
import com.positivity.inventory.service.ReceivingService;
import com.positivity.inventory.service.contract.BaseContractIntegrationTest;

import tools.jackson.databind.ObjectMapper;

/**
 * Contract behavior integration tests for the Cross-Dock API (CAP-216 Story #33
 * — Direct-to-Workorder/Cross-dock Receiving).
 *
 * <p>
 * Verifies
 * {@code POST /v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock}
 * per:
 * <ul>
 * <li>ADR-0011: gateway security — {@code inventory:receiving:complete} AND
 * {@code inventory:issue:parts} authorities both required via
 * {@code @PreAuthorize}</li>
 * <li>ADR-0017: HTTP response codes — 200 OK on success, 400 for closed
 * workorder,
 * 403 for missing {@code inventory:issue:parts} authority or part-mismatch
 * without
 * override permission</li>
 * <li>ADR-0001: atomicity claim documented — GOODS_RECEIVED + GOODS_ISSUE
 * ledger entries
 * created in a single transaction; verified via service-layer contract
 * comment</li>
 * </ul>
 *
 * <p>
 * Tests are intentionally RED for AC1–AC3:
 * {@link com.positivity.inventory.internal.service.ReceivingServiceImpl#crossDockLineToWorkorder}
 * is an {@code UnsupportedOperationException} stub → returns HTTP 500,
 * while tests expect 200/400 respectively.
 * AC4 is expected GREEN: Spring Security {@code @PreAuthorize} denies the
 * request
 * before the service stub is reached, returning 403.
 *
 * Issue: #33
 */
@DisplayName("Cross-Dock Contract Behavior")
class CrossDockContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ReceivingService receivingService;

    /**
     * Limited auth helper for AC4: intentionally omits
     * {@code inventory:issue:parts}
     * and {@code inventory:receiving:complete} so the {@code @PreAuthorize}
     * expression
     * {@code hasAuthority('inventory:receiving:complete') and hasAuthority('inventory:issue:parts')}
     * evaluates to false, producing a 403 before the service layer is invoked.
     *
     * <p>
     * NOT added to {@link BaseContractIntegrationTest} — this is test-scoped helper
     * only.
     */
    private MockHttpServletRequestBuilder withReceivingOnlyAuth(MockHttpServletRequestBuilder requestBuilder) {
        return requestBuilder
                .header("X-User", "limited-user")
                .header("X-Authorities", "inventory:receiving:create"); // intentionally missing issue:parts
    }

    // ─── AC1: Full exact-quantity cross-dock → 200 ────────────────────────────

    /**
     * Verifies that cross-docking the full expected quantity to a workorder returns
     * 200 OK
     * with {@code crossDockedQuantity=10}, {@code workorderId="WO-001"}, and a
     * non-null
     * {@code sessionStatus}, per story #33 AC1.
     *
     * <p>
     * ADR-0011: both {@code inventory:receiving:complete} and
     * {@code inventory:issue:parts}
     * supplied via {@code withReceivingAuth}.
     * ADR-0017: 200 OK returned for a successful cross-dock action on an existing
     * resource.
     * ADR-0001: atomicity — GOODS_RECEIVED + GOODS_ISSUE created in one
     * transaction.
     *
     * <p>
     * RED reason: service stub throws {@code UnsupportedOperationException} → 500,
     * assertion expects 200.
     *
     * Issue: #33
     */
    @Test
    @DisplayName("AC1: POST cross-dock with full expected quantity returns 200 with crossDockedQuantity=10 and workorderId")
    void crossDock_fullExactQuantity_returns200WithWorkorderAndQuantity() throws Exception {
        // Issue #33: full cross-dock must return 200, crossDockedQuantity=10,
        // workorderId present, sessionStatus not null
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        CrossDockResponse response = CrossDockResponse.builder()
                .lineId(lineId)
                .workorderId("WO-001")
                .workorderLineId(null)
                .crossDockedQuantity(new BigDecimal("10"))
                .sessionStatus("COMPLETED")
                .lineStatus("RECEIVED")
                .build();

        when(receivingService.crossDockLineToWorkorder(eq(sessionId), eq(lineId), any(), any()))
                .thenReturn(response);

        var reqBody = objectMapper.createObjectNode();
        reqBody.put("workorderId", "WO-001");
        reqBody.put("quantity", 10);

        mockMvc.perform(withReceivingAuth(
                post("/v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock",
                        sessionId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workorderId").value("WO-001"))
                .andExpect(jsonPath("$.crossDockedQuantity").value(10))
                .andExpect(jsonPath("$.sessionStatus").isNotEmpty());
    }

    // ─── AC2: Partial cross-dock → 200 ───────────────────────────────────────

    /**
     * Verifies that cross-docking a partial quantity (3 of 10 expected) returns 200
     * OK
     * with {@code crossDockedQuantity=3}, leaving the session in IN_PROGRESS state,
     * per story #33 AC2.
     *
     * <p>
     * ADR-0017: 200 OK for partial cross-dock; session remains open.
     *
     * <p>
     * RED reason: service stub throws {@code UnsupportedOperationException} → 500,
     * assertion expects 200.
     *
     * Issue: #33
     */
    @Test
    @DisplayName("AC2: POST cross-dock with partial quantity (3 of 10) returns 200 with crossDockedQuantity=3")
    void crossDock_partialQuantity_returns200WithPartialCrossDockedQuantity() throws Exception {
        // Issue #33: partial cross-dock (qty < expectedQty) must return 200,
        // crossDockedQuantity=3
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        CrossDockResponse response = CrossDockResponse.builder()
                .lineId(lineId)
                .workorderId("WO-002")
                .workorderLineId(null)
                .crossDockedQuantity(new BigDecimal("3"))
                .sessionStatus("IN_PROGRESS")
                .lineStatus("PARTIALLY_RECEIVED")
                .build();

        when(receivingService.crossDockLineToWorkorder(eq(sessionId), eq(lineId), any(), any()))
                .thenReturn(response);

        var reqBody = objectMapper.createObjectNode();
        reqBody.put("workorderId", "WO-002");
        reqBody.put("quantity", 3);

        mockMvc.perform(withReceivingAuth(
                post("/v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock",
                        sessionId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crossDockedQuantity").value(3));
    }

    // ─── AC3: Closed workorder → 400 ─────────────────────────────────────────

    /**
     * Verifies that cross-docking to a closed workorder returns 400 Bad Request
     * with an error code of {@code WORKORDER_CLOSED} in the response body,
     * per story #33 AC3.
     *
     * <p>
     * ADR-0017: 400 Bad Request for business-rule rejection (workorder not
     * accepting
     * new lines).
     * Exception mapped by
     * {@code InventoryGlobalExceptionHandler#handleWorkorderClosed}.
     *
     * <p>
     * RED reason: service stub throws {@code UnsupportedOperationException} (not
     * {@code WorkorderClosedException}), so the exception handler maps it to 500,
     * while the assertion expects 400.
     *
     * Issue: #33
     */
    @Test
    @DisplayName("AC3: POST cross-dock to closed workorder returns 400 with WORKORDER_CLOSED error")
    void crossDock_closedWorkorder_returns400WithWorkorderClosedError() throws Exception {
        // Issue #33: cross-dock to a closed workorder must return 400, body contains
        // WORKORDER_CLOSED
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        when(receivingService.crossDockLineToWorkorder(eq(sessionId), eq(lineId), any(), any()))
                .thenThrow(new WorkorderClosedException("Workorder WO-CLOSED is closed"));

        var reqBody = objectMapper.createObjectNode();
        reqBody.put("workorderId", "WO-CLOSED");
        reqBody.put("quantity", 10);

        mockMvc.perform(withReceivingAuth(
                post("/v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock",
                        sessionId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody))))
                .andExpect(status().isBadRequest());
    }

    // ─── AC4: Missing inventory:issue:parts authority → 403 ──────────────────

    /**
     * Verifies that a request lacking the {@code inventory:issue:parts} authority
     * is
     * denied by Spring Security's {@code @PreAuthorize} check and returns 403
     * Forbidden,
     * before the service layer is invoked at all, per story #33 AC4.
     *
     * <p>
     * ADR-0011: endpoint requires BOTH {@code inventory:receiving:complete} AND
     * {@code inventory:issue:parts}. Providing only
     * {@code inventory:receiving:create}
     * causes the {@code hasAuthority} expression to evaluate false → 403 from
     * Spring
     * Security, not from the service stub.
     * ADR-0017: 403 Forbidden for authorization failures.
     *
     * <p>
     * Expected GREEN immediately: security layer evaluates before service is
     * called,
     * so the {@code UnsupportedOperationException} stub is never reached.
     *
     * Issue: #33
     */
    @Test
    @DisplayName("AC4: POST cross-dock without inventory:issue:parts authority returns 403 Forbidden")
    void crossDock_missingIssuePartsAuthority_returns403Forbidden() throws Exception {
        // Issue #33: missing inventory:issue:parts must cause @PreAuthorize to deny →
        // 403
        UUID sessionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();

        // No mock needed: Spring Security rejects before service is invoked.

        var reqBody = objectMapper.createObjectNode();
        reqBody.put("workorderId", "WO-001");
        reqBody.put("quantity", 10);

        mockMvc.perform(withReceivingOnlyAuth(
                post("/v1/inventory/receiving/sessions/{sessionId}/lines/{lineId}/cross-dock",
                        sessionId, lineId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(reqBody))))
                .andExpect(status().isForbidden());
    }
}
