package com.positivity.workorder.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.workorder.config.TestSecurityConfig;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.service.WorkorderSearchService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Controller-layer tests for the E12 (#1600) structured filters on {@code GET
 * /v1/workorders/search}: routing/delegation for the new status, createdFrom/createdTo, and
 * technicianId params, the unknown-status 400 path, the page-size cap, and permission
 * enforcement. Pre-existing q/customerId/vehicleId behavior is covered by
 * {@code WorkorderSearchServiceTest}; this class focuses on the controller boundary.
 */
@WebMvcTest(WorkorderSearchController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
class WorkorderSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkorderSearchService workorderSearchService;

    private static final UUID CUSTOMER_ID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    private static final UUID TECHNICIAN_ID = UUID.fromString("cccccccc-0000-0000-0000-000000000001");

    @Test
    @DisplayName("GET /search with only q delegates with null structured filters and returns 200")
    void search_existingParamsOnly_delegatesWithNullStructuredFilters() throws Exception {
        when(workorderSearchService.search(
                        eq("brakes"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<WorkorderSearchResult>(List.of()));

        mockMvc.perform(get("/v1/workorders/search").param("q", "brakes")).andExpect(status().isOk());

        verify(workorderSearchService)
                .search(eq("brakes"), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("GET /search?status=APPROVED&customerId=... is the Q5 gate combo (one open status + customer)")
    void search_openStatusPlusCustomerId_isCombinableInOneCall() throws Exception {
        when(workorderSearchService.search(
                        eq(""),
                        eq(CUSTOMER_ID),
                        isNull(),
                        eq(WorkorderStatus.APPROVED),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<WorkorderSearchResult>(List.of()));

        mockMvc.perform(get("/v1/workorders/search")
                        .param("customerId", CUSTOMER_ID.toString())
                        .param("status", "APPROVED"))
                .andExpect(status().isOk());

        verify(workorderSearchService)
                .search(
                        eq(""),
                        eq(CUSTOMER_ID),
                        isNull(),
                        eq(WorkorderStatus.APPROVED),
                        isNull(),
                        isNull(),
                        isNull(),
                        any(Pageable.class));
    }

    @Test
    @DisplayName("GET /search?status=NOT_A_REAL_STATUS returns 400 rather than an empty page")
    void search_unknownStatusValue_returns400() throws Exception {
        mockMvc.perform(get("/v1/workorders/search").param("status", "NOT_A_REAL_STATUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /search with createdFrom/createdTo forwards the date window")
    void search_createdDateWindow_forwardsToService() throws Exception {
        when(workorderSearchService.search(
                        eq(""),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(LocalDate.of(2026, 6, 1)),
                        eq(LocalDate.of(2026, 6, 30)),
                        isNull(),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<WorkorderSearchResult>(List.of()));

        mockMvc.perform(get("/v1/workorders/search")
                        .param("createdFrom", "2026-06-01")
                        .param("createdTo", "2026-06-30"))
                .andExpect(status().isOk());

        verify(workorderSearchService)
                .search(
                        eq(""),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(LocalDate.of(2026, 6, 1)),
                        eq(LocalDate.of(2026, 6, 30)),
                        isNull(),
                        any(Pageable.class));
    }

    @Test
    @DisplayName("GET /search with technicianId forwards the filter")
    void search_technicianId_forwardsToService() throws Exception {
        when(workorderSearchService.search(
                        eq(""),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(TECHNICIAN_ID),
                        any(Pageable.class)))
                .thenReturn(new PageImpl<WorkorderSearchResult>(List.of()));

        mockMvc.perform(get("/v1/workorders/search").param("technicianId", TECHNICIAN_ID.toString()))
                .andExpect(status().isOk());

        verify(workorderSearchService)
                .search(
                        eq(""),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        isNull(),
                        eq(TECHNICIAN_ID),
                        any(Pageable.class));
    }

    @Test
    @DisplayName("GET /search?size=500 is clamped to the documented 100-row page-size cap")
    void search_requestedSizeAboveCap_isClampedTo100() throws Exception {
        when(workorderSearchService.search(
                        eq(""), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.<WorkorderSearchResult>of(), PageRequest.of(0, 100), 0));

        mockMvc.perform(get("/v1/workorders/search").param("size", "500")).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(workorderSearchService)
                .search(eq(""), isNull(), isNull(), isNull(), isNull(), isNull(), isNull(), pageableCaptor.capture());
        assertThat(pageableCaptor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    @DisplayName("GET /search without workorder:workorder:view is forbidden")
    void search_missingPermission_returns403() throws Exception {
        mockMvc.perform(get("/v1/workorders/search")
                        .param("q", "brakes")
                        .header("X-Authorities", "workorder:analytics:view"))
                .andExpect(status().isForbidden());
    }
}
