package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.cyclecount.service.CycleCountAdjustmentService;
import com.positivity.inventory.internal.dto.cyclecount.AdjustmentResponse;
import com.positivity.inventory.internal.enums.AdjustmentStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Who may approve a cycle count write-off, and what an approver can read (#2149).
 *
 * <p>#2149 was a location manager refused {@code POST /v1/inventory/cycleCountAdjustments/{id}/approve}
 * on alpha with a bare {@code FORBIDDEN}, which ended an accelerated run on virtual day 7: the weekly
 * cycle count is a scheduled step, so any run longer than a week reaches it. The issue guessed the
 * authority was {@code inventory:cycle_count:approve}; no such permission exists. The endpoint
 * enforces {@code inventory:adjustment:approve}, and LOCATION_MANAGER held no grant from the
 * adjustment family at all — in the repo as well as on the tenant, which is what separates #2149
 * from #2138, where the grant was present in the CSV and missing only from the deployed tenant.
 *
 * <p>The load-bearing assertions here are the read ones. LOCATION_MANAGER was given
 * {@code inventory:adjustment:approve} and deliberately <em>not</em> {@code inventory:adjustment:view},
 * because every read on this controller accepts either. That makes the one grant sufficient for the
 * whole manager half of the flow — approve, reject, and the pending-approvals queue the manager works
 * from — and it makes {@code hasAnyAuthority} load-bearing rather than decorative. Narrowing any of
 * these four reads to {@code hasAuthority(ADJUSTMENT_VIEW)} would leave the manager able to approve an
 * adjustment it cannot open, so each read is pinned against an approve-only caller.
 *
 * <p>The counting half stays the clerk's: a caller holding the clerk's real grants
 * ({@code inventory:adjustment:create} plus the cycle-count family, which is INVENTORY_LEAD's row) is
 * still refused the approval, because counting and approving are deliberately different people.
 */
@WebMvcTest(CycleCountAdjustmentController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@DisplayName("cycle count adjustment approval authority (#2149)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CycleCountAdjustmentControllerTest {

    private static final UUID ADJUSTMENT_ID = UUID.fromString("01a0c6e0-c514-7f48-84ae-0f17965a3180");

    /** The one grant #2149 added to LOCATION_MANAGER — no {@code inventory:adjustment:view} alongside it. */
    private static final String APPROVE_ONLY = "inventory:adjustment:approve";

    /**
     * INVENTORY_LEAD's real adjustment and cycle-count grants, as
     * {@code scripts/fixtures/seed/alpha/security/role-permissions.csv} carries them.
     */
    private static final String CLERK_GRANTS = "inventory:adjustment:create,inventory:adjustment:view,"
            + "inventory:cycle_count:initiate,inventory:cycle_count:view,inventory:cycle_count:complete";

    @Autowired
    MockMvc mockMvc;

    /** {@code InventoryGlobalExceptionHandler} takes one; the web slice provides no real clock. */
    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    CycleCountAdjustmentService adjustmentService;

    private AdjustmentResponse sampleResponse(AdjustmentStatus status) {
        return AdjustmentResponse.builder()
                .adjustmentId(ADJUSTMENT_ID)
                .status(status)
                .quantityChange(new BigDecimal("-3"))
                .build();
    }

    // ─── POST /{adjustmentId}/approve ────────────────────────────────────────

    @Test
    @DisplayName("the manager's single grant approves the write-off")
    void approve_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.approveAdjustment(eq(ADJUSTMENT_ID), any(), any()))
                .thenReturn(sampleResponse(AdjustmentStatus.APPROVED));

        mockMvc.perform(post("/v1/inventory/cycleCountAdjustments/{id}/approve", ADJUSTMENT_ID)
                        .header("X-Authorities", APPROVE_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"notes":"Variance confirmed against receiving log"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustmentId").value(ADJUSTMENT_ID.toString()))
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    @DisplayName("the clerk who raised the count may not approve it")
    void approve_withTheClerksGrants_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/cycleCountAdjustments/{id}/approve", ADJUSTMENT_ID)
                        .header("X-Authorities", CLERK_GRANTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"notes\":\"self-approval\"}"))
                .andExpect(status().isForbidden());

        verify(adjustmentService, never()).approveAdjustment(any(), any(), any());
    }

    // ─── POST /{adjustmentId}/reject ─────────────────────────────────────────

    @Test
    @DisplayName("approving and rejecting are the same authority")
    void reject_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.rejectAdjustment(eq(ADJUSTMENT_ID), any()))
                .thenReturn(sampleResponse(AdjustmentStatus.REJECTED));

        mockMvc.perform(post("/v1/inventory/cycleCountAdjustments/{id}/reject", ADJUSTMENT_ID)
                        .header("X-Authorities", APPROVE_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"rejectorUserId":"diana.rowe","rejectionReason":"Recount ordered"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ─── the reads an approver works from, without inventory:adjustment:view ──

    @Test
    @DisplayName("an approver can open the adjustment it is being asked to approve")
    void getAdjustment_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.getAdjustment(ADJUSTMENT_ID))
                .thenReturn(sampleResponse(AdjustmentStatus.PENDING_APPROVAL));

        mockMvc.perform(get("/v1/inventory/cycleCountAdjustments/{id}", ADJUSTMENT_ID)
                        .header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.adjustmentId").value(ADJUSTMENT_ID.toString()));
    }

    @Test
    @DisplayName("an approver can list adjustments by status")
    void listAdjustments_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                .thenReturn(List.of(sampleResponse(AdjustmentStatus.PENDING_APPROVAL)));

        mockMvc.perform(get("/v1/inventory/cycleCountAdjustments")
                        .param("status", "PENDING_APPROVAL")
                        .header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].adjustmentId").value(ADJUSTMENT_ID.toString()));
    }

    @Test
    @DisplayName("an approver can work the pending-approvals queue")
    void listPendingApprovals_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.listAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                .thenReturn(List.of(sampleResponse(AdjustmentStatus.PENDING_APPROVAL)));

        mockMvc.perform(get("/v1/inventory/cycleCountAdjustments/pending").header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("PENDING_APPROVAL"));
    }

    @Test
    @DisplayName("an approver can read the pending-approvals badge count")
    void countPendingApprovals_withApproveAuthorityAlone_returns200() throws Exception {
        when(adjustmentService.countAdjustmentsByStatus(AdjustmentStatus.PENDING_APPROVAL))
                .thenReturn(4L);

        mockMvc.perform(get("/v1/inventory/cycleCountAdjustments/pending/count").header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").value(4));
    }

    @Test
    @DisplayName("a caller holding no adjustment grant reads nothing")
    void reads_withoutAnyAdjustmentGrant_return403() throws Exception {
        mockMvc.perform(get("/v1/inventory/cycleCountAdjustments/pending")
                        .header("X-Authorities", "inventory:cycle_count:view"))
                .andExpect(status().isForbidden());

        verify(adjustmentService, never()).listAdjustmentsByStatus(any());
    }
}
