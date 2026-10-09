package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.InputTaxRecoveryResponse;
import com.positivity.accounting.internal.dto.PettyExpenseCategoryTaxRecoveryResponse;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.service.InputTaxRecoveryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The input-tax recovery endpoints (CAP:550 S32d items 2 and 4; AC 14; review of #2664 B3): the permission gates and
 * the PUT's status mapping, 400, 409 {@code OPTIMISTIC_LOCK}, 422 {@code INPUT_TAX_RECOVERY_NOT_ENABLED} and 503 {@code
 * SERVICE_UNAVAILABLE} with {@code Retry-After}. The service's own decisions are in {@code InputTaxRecoveryServiceTest}.
 */
@DisplayName("Input-tax recovery endpoints: permissions and status mapping (S32d)")
class InputTaxRecoveryControllerTest extends BaseIntegrationTest {

    private static final String READ = "/v1/accounting/input-tax-recovery";
    private static final String PUT = "/v1/accounting/petty-expense-categories/STAFF_MEALS/tax-recovery";
    private static final String BODY = """
            {"taxRecoverable":true,"recoverablePercent":50.00,"version":0,
             "justification":"Meals are half recoverable under the regime",
             "requestId":"019a0000-0000-7000-8000-000000000107"}
            """;

    @MockitoBean
    private InputTaxRecoveryService service;

    @Test
    @DisplayName("the read is 403 without accounting:mapping-key:view and 200 with it")
    void readIsGated() throws Exception {
        when(service.read())
                .thenReturn(new InputTaxRecoveryResponse(
                        List.of(), List.of(), List.of(), List.of(), Instant.parse("2026-10-08T12:00:00Z")));

        mockMvc.perform(withAuth(get(READ), "accounting:coa:view")).andExpect(status().isForbidden());
        verify(service, never()).read();

        mockMvc.perform(withAuth(get(READ), "accounting:mapping-key:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.regimes").isArray())
                .andExpect(jsonPath("$.regimes").isEmpty())
                .andExpect(jsonPath("$.asOf").exists());
    }

    @Test
    @DisplayName("the PUT is 403 without accounting:mapping-key:edit and 200 with it")
    void putIsGated() throws Exception {
        when(service.setCategoryRecovery(eq("STAFF_MEALS"), any()))
                .thenReturn(new PettyExpenseCategoryTaxRecoveryResponse(
                        "STAFF_MEALS", true, new BigDecimal("50.00"), 1, Instant.parse("2026-10-08T12:00:00Z"), false));

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isForbidden());
        verify(service, never()).setCategoryRecovery(any(), any());

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("STAFF_MEALS"))
                .andExpect(jsonPath("$.recoverablePercent").value(50.00))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    @Test
    @DisplayName("AC 14: a justification under 10 characters is 400 VALIDATION_ERROR")
    void shortJustificationIs400() throws Exception {
        when(service.setCategoryRecovery(eq("STAFF_MEALS"), any()))
                .thenThrow(new InvalidRequestParameterException("justification must be at least 10 characters"));

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY.replace("Meals are half recoverable under the regime", "short")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("AC 14: a stale version is 409 OPTIMISTIC_LOCK")
    void staleVersionIs409() throws Exception {
        when(service.setCategoryRecovery(eq("STAFF_MEALS"), any()))
                .thenThrow(new CashSetupException(CashSetupException.Code.OPTIMISTIC_LOCK, "read it again"));

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("OPTIMISTIC_LOCK"));
    }

    @Test
    @DisplayName("AC 14: no regime enabled is 422 INPUT_TAX_RECOVERY_NOT_ENABLED")
    void notEnabledIs422() throws Exception {
        when(service.setCategoryRecovery(eq("STAFF_MEALS"), any()))
                .thenThrow(new CashSetupException(
                        CashSetupException.Code.INPUT_TAX_RECOVERY_NOT_ENABLED, "No regime's recovery is on today"));

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("INPUT_TAX_RECOVERY_NOT_ENABLED"));
    }

    @Test
    @DisplayName("D2: flags that cannot be obtained are 503 SERVICE_UNAVAILABLE with Retry-After")
    void flagsUnavailableIs503() throws Exception {
        when(service.setCategoryRecovery(eq("STAFF_MEALS"), any()))
                .thenThrow(new TaxServiceUnavailableException("The tax configuration is unavailable"));

        mockMvc.perform(withAuth(put(PUT), "accounting:mapping-key:edit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
