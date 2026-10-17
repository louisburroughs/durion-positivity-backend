package com.positivity.accounting.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.internal.dto.CashPositionResponse;
import com.positivity.accounting.internal.dto.CashPositionResponse.AccountRow;
import com.positivity.accounting.internal.dto.CashPositionResponse.Component;
import com.positivity.accounting.internal.dto.CashPositionResponse.NeedsAttention;
import com.positivity.accounting.internal.enums.CashAttentionCode;
import com.positivity.accounting.internal.service.CashPositionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

/** The cash position read of #2515 through the production security chain: 200, 403, and its annotations. */
@DisplayName("CashPositionController (#2515)")
class CashPositionControllerTest extends CashWorkspaceControllerTestBase {

    private static final String VIEW = "reporting:view:financial-statements";
    private static final String PATH = "/v1/accounting/cash-position";
    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-000000001000");


    @Test
    @DisplayName("200 with the total, the components and the needs-attention items")
    void reads() throws Exception {
        when(cashPositionService.read())
                .thenReturn(new CashPositionResponse(
                        Instant.parse("2026-10-17T15:00:00Z"),
                        null,
                        false,
                        "USD",
                        new BigDecimal("14197.00"),
                        new Component(
                                new BigDecimal("12400.00"),
                                List.of(new AccountRow(
                                        BANK,
                                        "1000",
                                        "Operating Bank",
                                        new BigDecimal("12400.00"),
                                        null,
                                        null,
                                        null,
                                        null,
                                        null,
                                        null))),
                        new Component(new BigDecimal("1197.00"), List.of()),
                        new Component(new BigDecimal("600.00"), List.of()),
                        1,
                        List.of(new NeedsAttention(
                                CashAttentionCode.SETTLEMENT_SUSPENSE_NOT_ZERO,
                                null,
                                new BigDecimal("35.00"),
                                null,
                                LocalDate.of(2026, 10, 1))),
                        null));

        mockMvc.perform(withAuth(get(PATH), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(14197.00))
                .andExpect(jsonPath("$.inTheBank.total").value(12400.00))
                .andExpect(jsonPath("$.inTheBank.accounts[0].accountCode").value("1000"))
                .andExpect(jsonPath("$.inTheBank.accounts[0].statementClosingBalance")
                        .doesNotExist())
                .andExpect(jsonPath("$.waitingToBeDeposited.total").value(1197.00))
                .andExpect(jsonPath("$.keptInDrawers.total").value(600.00))
                .andExpect(jsonPath("$.excludedForeignCurrencyAccounts").value(1))
                .andExpect(jsonPath("$.needsAttention[0].code").value("SETTLEMENT_SUSPENSE_NOT_ZERO"))
                .andExpect(jsonPath("$.needsAttention[0].amount").value(35.00))
                .andExpect(jsonPath("$.needsAttention[0].since").value("2026-10-01"))
                .andExpect(jsonPath("$.lagging").value(false));
    }

    @Test
    @DisplayName("403 without reporting:view:financial-statements")
    void forbidden() throws Exception {
        mockMvc.perform(withAuth(get(PATH), "accounting:reconciliation:view")).andExpect(status().isForbidden());

        verify(cashPositionService, never()).read();
    }

    @Test
    @DisplayName("operation id, permission and the ACCOUNTING_CASH_POSITION_GET event")
    void annotations() throws Exception {
        Method read = CashPositionController.class.getMethod("getCashPosition");

        assertThat(read.getAnnotation(Operation.class).operationId()).isEqualTo("getCashPosition");
        assertThat(read.getAnnotation(EmitEvent.class).id()).isEqualTo("ACCOUNTING_CASH_POSITION_GET");
        assertThat(read.getAnnotation(PreAuthorize.class).value()).contains(VIEW);
    }
}
