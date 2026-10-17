package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.CashOutlookResponse;
import com.positivity.accounting.internal.dto.CashOutlookResponse.BillsLane;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Figure;
import com.positivity.accounting.internal.dto.CashOutlookResponse.Point;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.service.CashOutlookService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The cash outlook read of #2515 through the production security chain: 200, 400 for days, 403, annotations. */
@DisplayName("CashOutlookController (#2515)")
class CashOutlookControllerTest extends BaseIntegrationTest {

    private static final String VIEW = "reporting:view:financial-statements";
    private static final String PATH = "/v1/accounting/cash-outlook";

    @MockitoBean
    private CashOutlookService service;

    private static CashOutlookResponse outlook(int days) {
        Figure none = new Figure(new BigDecimal("0.00"), 0);
        Point first = new Point(
                LocalDate.of(2026, 10, 17),
                new BigDecimal("11000.00"),
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO);
        return new CashOutlookResponse(
                Instant.parse("2026-10-17T15:00:00Z"),
                true,
                "USD",
                LocalDate.of(2026, 10, 17),
                days,
                new BigDecimal("11000.00"),
                List.of(first),
                first,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                new Figure(new BigDecimal("300.00"), 1),
                none,
                none,
                none,
                new BillsLane(none, none, none, none, BigDecimal.ZERO),
                null,
                false);
    }

    @Test
    @DisplayName("200 with the projection; days defaults to 30")
    void readsWithTheDefault() throws Exception {
        when(service.outlook(30)).thenReturn(outlook(30));

        mockMvc.perform(withAuth(get(PATH), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projection").value(true))
                .andExpect(jsonPath("$.days").value(30))
                .andExpect(jsonPath("$.startingBalance").value(11000.00))
                .andExpect(jsonPath("$.points[0].date").value("2026-10-17"))
                .andExpect(jsonPath("$.lowPoint.balance").value(11000.00))
                .andExpect(jsonPath("$.possibleOut.amount").value(300.00))
                .andExpect(jsonPath("$.safetyCushion").doesNotExist())
                .andExpect(jsonPath("$.belowCushion").value(false));
    }

    @Test
    @DisplayName("200 passes an explicit days through")
    void readsWithDays() throws Exception {
        when(service.outlook(7)).thenReturn(outlook(7));

        mockMvc.perform(withAuth(get(PATH).param("days", "7"), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR when days is outside 1 to 90")
    void daysOutOfRange() throws Exception {
        when(service.outlook(91))
                .thenThrow(InvalidRequestParameterException.forField("days", "must be from 1 to 90"));

        mockMvc.perform(withAuth(get(PATH).param("days", "91"), VIEW))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("days"));
    }

    @Test
    @DisplayName("400 when days is not a number")
    void daysNotANumber() throws Exception {
        mockMvc.perform(withAuth(get(PATH).param("days", "soon"), VIEW)).andExpect(status().isBadRequest());

        verify(service, never()).outlook(anyInt());
    }

    @Test
    @DisplayName("403 without reporting:view:financial-statements")
    void forbidden() throws Exception {
        mockMvc.perform(withAuth(get(PATH), "accounting:reconciliation:view")).andExpect(status().isForbidden());

        verify(service, never()).outlook(anyInt());
    }

    @Test
    @DisplayName("operation id, permission and the ACCOUNTING_CASH_OUTLOOK_GET event")
    void annotations() throws Exception {
        Method read = CashOutlookController.class.getMethod("getCashOutlook", int.class);

        assertThat(read.getAnnotation(Operation.class).operationId()).isEqualTo("getCashOutlook");
        assertThat(read.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_CASH_OUTLOOK_GET");
        assertThat(read.getAnnotation(PreAuthorize.class).value()).contains(VIEW);
    }
}
