package com.positivity.inventory.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.dto.valuation.ValuationReportResponse;
import com.positivity.inventory.internal.dto.valuation.ValuationRow;
import com.positivity.inventory.service.ValuationService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Web-slice tests for {@link ValuationController} (odoo-parity J2, #1052): the dedicated
 * {@code inventory:valuation:view} permission gates the reads (403 without it, 200 with), and the
 * as-of variant's history-exposure guard surfaces 403 as an ApiError.
 */
@WebMvcTest(ValuationController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class ValuationControllerTest {

    private static final String VALUATION_VIEW = "inventory:valuation:view";
    private static final String ON_HAND_VIEW = "inventory:on_hand:view";
    private static final String LEDGER_VIEW = "inventory:ledger:view";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    ValuationService valuationService;

    private ValuationReportResponse sample() {
        return ValuationReportResponse.builder()
                .rows(List.of(ValuationRow.builder()
                        .stockItemId("PART-A")
                        .onHand(20L)
                        .costingMethod("AVERAGE")
                        .unitCostCurrent(new BigDecimal("6.0000"))
                        .onHandValue(new BigDecimal("120.0000"))
                        .uncosted(false)
                        .build()))
                .totalOnHandValue(new BigDecimal("120.0000"))
                .build();
    }

    @Test
    void valuation_withAuthority_returns200() throws Exception {
        when(valuationService.getValuation(isNull(), isNull())).thenReturn(sample());

        mockMvc.perform(get("/v1/inventory/valuation").header("X-Authorities", VALUATION_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].stockItemId").value("PART-A"))
                .andExpect(jsonPath("$.rows[0].onHandValue").value(120.0000))
                .andExpect(jsonPath("$.totalOnHandValue").value(120.0000));
    }

    @Test
    void valuation_missingAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/valuation").header("X-Authorities", ON_HAND_VIEW))
                .andExpect(status().isForbidden());
    }

    @Test
    void valuation_noAuthorities_returns403() throws Exception {
        mockMvc.perform(get("/v1/inventory/valuation")).andExpect(status().isForbidden());
    }

    @Test
    void valuation_asOf_delegatesToAsOfPath() throws Exception {
        Instant asOf = Instant.parse("2026-06-01T00:00:00Z");
        when(valuationService.getValuationAsOf(isNull(), isNull(), eq(asOf))).thenReturn(sample());

        mockMvc.perform(get("/v1/inventory/valuation")
                        .param("asOf", "2026-06-01T00:00:00Z")
                        .header("X-Authorities", VALUATION_VIEW + "," + LEDGER_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows[0].stockItemId").value("PART-A"));
    }
}
