package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileRequest;
import com.positivity.accounting.internal.dto.InvoiceRevenueReconcileResponse;
import com.positivity.accounting.internal.service.InvoiceRevenueReconciliationService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** #1851: the reconcile endpoint is permission-gated and tolerates an absent body. */
@DisplayName("InvoiceRevenueReconciliationController")
class InvoiceRevenueReconciliationControllerTest extends BaseIntegrationTest {

    private static final String URL = "/v1/accounting/invoice-revenue/reconcile";

    @MockitoBean
    private InvoiceRevenueReconciliationService reconciliationService;

    @Test
    @DisplayName("200 with totals for a bounded dry run when the caller holds accounting:gl:reconcile")
    void reconcile_returns200_withAuthority() throws Exception {
        when(reconciliationService.reconcile(any()))
                .thenReturn(new InvoiceRevenueReconcileResponse(true, 3, 2, 1, 0, 0, List.of()));

        mockMvc.perform(
                        post(URL)
                                .header("X-Authorities", "accounting:gl:reconcile")
                                .header("X-User", "ops-user")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"finalizedFrom\":\"2026-07-01T00:00:00Z\",\"finalizedTo\":\"2026-09-01T00:00:00Z\",\"dryRun\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.scanned").value(3))
                .andExpect(jsonPath("$.posted").value(2))
                .andExpect(jsonPath("$.alreadyPosted").value(1));

        ArgumentCaptor<InvoiceRevenueReconcileRequest> request =
                ArgumentCaptor.forClass(InvoiceRevenueReconcileRequest.class);
        verify(reconciliationService).reconcile(request.capture());
        org.assertj.core.api.Assertions.assertThat(request.getValue().dryRunEnabled())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(request.getValue().finalizedFrom())
                .isNotNull();
    }

    @Test
    @DisplayName("an absent body reconciles everything, live")
    void reconcile_withoutBody_reconcilesEverything() throws Exception {
        when(reconciliationService.reconcile(any()))
                .thenReturn(new InvoiceRevenueReconcileResponse(false, 0, 0, 0, 0, 0, List.of()));

        mockMvc.perform(post(URL)
                        .header("X-Authorities", "accounting:gl:reconcile")
                        .header("X-User", "ops-user"))
                .andExpect(status().isOk());

        verify(reconciliationService).reconcile(InvoiceRevenueReconcileRequest.everything());
    }

    @Test
    @DisplayName("403 without accounting:gl:reconcile")
    void reconcile_returns403_withoutAuthority() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Authorities", "accounting:events:retry")
                        .header("X-User", "ops-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dryRun\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("400 when limit is out of range")
    void reconcile_returns400_forBadLimit() throws Exception {
        mockMvc.perform(post(URL)
                        .header("X-Authorities", "accounting:gl:reconcile")
                        .header("X-User", "ops-user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"limit\":0}"))
                .andExpect(status().isBadRequest());
    }
}
