package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.internal.scrap.service.ScrapService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.LocationScopeAutoConfiguration;
import com.positivity.security.common.LocationScopeDeniedException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * Who may approve a scrap write-off, and what an approver can read (#2472).
 *
 * <p>#2472 was a location manager refused {@code POST /v1/inventory/scraps/{scrapId}/approve} on
 * alpha with a bare {@code FORBIDDEN}: the accelerated run raises a scrap about every ten virtual
 * days, the inventory clerk creates it and the manager approves it, and all 29 approvals were
 * refused. The endpoint enforces {@code inventory:scrap:approve}, which LOCATION_MANAGER had never
 * held. It is the scrap twin of #2149, where the same role could not approve a cycle count
 * write-off.
 *
 * <p>LOCATION_MANAGER was given {@code inventory:scrap:approve} and deliberately <em>not</em>
 * {@code inventory:scrap:view}, because the scrap reads accept either. The read assertions pin that:
 * narrowing {@code getScrap} or {@code listScraps} to {@code hasAuthority(SCRAP_VIEW)} would leave
 * the manager able to approve a scrap it cannot open.
 *
 * <p>The creating half stays the clerk's: a caller holding INVENTORY_LEAD's real scrap grants is
 * refused the approval. Approve and reject are gated in the service on the scrap's location, so the
 * last test checks the service's denial reaches the client as {@code 403 LOCATION_SCOPE_DENIED};
 * {@link LocationScopeAutoConfiguration} is imported because a {@code @WebMvcTest} slice does not
 * load library auto-configuration on its own.
 */
@WebMvcTest(ScrapController.class)
@Import({TestSecurityConfig.class, LocationScopeAutoConfiguration.class})
@ActiveProfiles("test")
@DisplayName("scrap approval authority (#2472)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class ScrapControllerTest {

    private static final UUID SCRAP_ID = UUID.fromString("01a104f5-b7a8-7a15-9485-fde877063939");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    /** The one scrap grant #2472 added to LOCATION_MANAGER — no {@code inventory:scrap:view} alongside it. */
    private static final String APPROVE_ONLY = InventoryPermissionRegistry.SCRAP_APPROVE;

    /**
     * INVENTORY_LEAD's real scrap grants, as {@code scripts/fixtures/seed/alpha/security/role-permissions.csv}
     * carries them.
     */
    private static final String CLERK_GRANTS = "inventory:scrap:create,inventory:scrap:view";

    @Autowired
    MockMvc mockMvc;

    /**
     * The exception handlers take one; the web slice provides no real clock. Stubbed because the
     * {@code LOCATION_SCOPE_DENIED} envelope reads it for its timestamp.
     */
    @MockitoBean
    Clock clock;

    @MockitoBean
    ScrapService scrapService;

    @BeforeEach
    void stubClock() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-04T00:00:00Z"));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private ScrapResponse sampleResponse(ScrapStatus status) {
        return ScrapResponse.builder()
                .scrapId(SCRAP_ID)
                .locationId(LOCATION_ID)
                .status(status)
                .quantity(3)
                .build();
    }

    // ─── POST /{scrapId}/approve ─────────────────────────────────────────────

    @Test
    @DisplayName("the manager's single grant approves the write-off")
    void approve_withApproveAuthorityAlone_returns200() throws Exception {
        when(scrapService.approveScrap(eq(SCRAP_ID), any())).thenReturn(sampleResponse(ScrapStatus.POSTED));

        mockMvc.perform(post("/v1/inventory/scraps/{id}/approve", SCRAP_ID)
                        .header("X-Authorities", APPROVE_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"negativeStockOverride\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scrapId").value(SCRAP_ID.toString()))
                .andExpect(jsonPath("$.status").value("POSTED"));
    }

    @Test
    @DisplayName("the clerk who raised the scrap may not approve it")
    void approve_withTheClerksGrants_returns403() throws Exception {
        mockMvc.perform(post("/v1/inventory/scraps/{id}/approve", SCRAP_ID)
                        .header("X-Authorities", CLERK_GRANTS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"negativeStockOverride\":false}"))
                .andExpect(status().isForbidden());

        verify(scrapService, never()).approveScrap(any(), any());
    }

    @Test
    @DisplayName("an approver outside the scrap's location is refused with LOCATION_SCOPE_DENIED")
    void approve_outsideReach_returns403LocationScopeDenied() throws Exception {
        when(scrapService.approveScrap(eq(SCRAP_ID), any()))
                .thenThrow(new LocationScopeDeniedException(APPROVE_ONLY, LOCATION_ID.toString()));

        mockMvc.perform(post("/v1/inventory/scraps/{id}/approve", SCRAP_ID)
                        .header("X-Authorities", APPROVE_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"negativeStockOverride\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(LocationScopeDeniedException.ERROR_CODE));
    }

    // ─── POST /{scrapId}/reject ──────────────────────────────────────────────

    @Test
    @DisplayName("approving and rejecting are the same authority")
    void reject_withApproveAuthorityAlone_returns200() throws Exception {
        when(scrapService.rejectScrap(eq(SCRAP_ID), any())).thenReturn(sampleResponse(ScrapStatus.REJECTED));

        mockMvc.perform(post("/v1/inventory/scraps/{id}/reject", SCRAP_ID)
                        .header("X-Authorities", APPROVE_ONLY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rejectionReason\":\"Part was recovered and restocked\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    // ─── the reads an approver works from, without inventory:scrap:view ───────

    @Test
    @DisplayName("an approver can open the scrap it is being asked to approve")
    void getScrap_withApproveAuthorityAlone_returns200() throws Exception {
        when(scrapService.getScrap(SCRAP_ID)).thenReturn(sampleResponse(ScrapStatus.PENDING_APPROVAL));

        mockMvc.perform(get("/v1/inventory/scraps/{id}", SCRAP_ID).header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scrapId").value(SCRAP_ID.toString()));
    }

    @Test
    @DisplayName("an approver can list the scraps pending approval")
    void listScraps_withApproveAuthorityAlone_returns200() throws Exception {
        when(scrapService.listScraps(isNull(), eq(ScrapStatus.PENDING_APPROVAL), isNull(), isNull(), isNull()))
                .thenReturn(List.of(sampleResponse(ScrapStatus.PENDING_APPROVAL)));

        mockMvc.perform(get("/v1/inventory/scraps")
                        .param("status", "PENDING_APPROVAL")
                        .header("X-Authorities", APPROVE_ONLY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].scrapId").value(SCRAP_ID.toString()));
    }

    @Test
    @DisplayName("a caller holding no scrap grant reads nothing")
    void reads_withoutAnyScrapGrant_return403() throws Exception {
        mockMvc.perform(get("/v1/inventory/scraps").header("X-Authorities", "inventory:adjustment:approve"))
                .andExpect(status().isForbidden());

        verify(scrapService, never()).listScraps(any(), any(), any(), any(), any());
    }
}
