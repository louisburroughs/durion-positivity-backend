package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.AgedThresholdsRequest;
import com.positivity.accounting.internal.dto.AgedThresholdsResponse;
import com.positivity.accounting.internal.dto.CashSafetyCushionRequest;
import com.positivity.accounting.internal.dto.CashSafetyCushionResponse;
import com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.IdempotencyConflictException;
import com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.service.AccountingConfigurationService;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("/v1/accounting/configuration: time zone (#2558), cash safety cushion and aged thresholds (#2515)")
class AccountingConfigurationControllerTest extends BaseIntegrationTest {

    private static final String PATH = "/v1/accounting/configuration/time-zone";

    // The same mock set as AccountingPeriodControllerTest, so the two share one cached application context instead
    // of adding one more to the 1 GiB CI test fork (#2558).
    @MockitoBean
    private AccountingPeriodService accountingPeriodService;

    @MockitoBean
    private AccountingConfigurationService accountingConfigurationService;

    private static String body(String zone) {
        return "{\"timeZone\":\"" + zone + "\"}";
    }

    @Test
    @DisplayName("200 with the stored zone")
    void setsTheZone() throws Exception {
        when(accountingConfigurationService.setAccountingTimeZone("America/Chicago"))
                .thenReturn("America/Chicago");

        mockMvc.perform(withAuth(put(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("America/Chicago")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.timeZone").value("America/Chicago"));
    }

    @Test
    @DisplayName("400 INVALID_ACCOUNTING_TIME_ZONE for a fixed offset")
    void invalidZoneIs400() throws Exception {
        when(accountingConfigurationService.setAccountingTimeZone("+05:00"))
                .thenThrow(new InvalidAccountingTimeZoneException("+05:00", "a fixed offset"));

        mockMvc.perform(withAuth(put(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("+05:00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ACCOUNTING_TIME_ZONE"));
    }

    @Test
    @DisplayName("400 for a blank zone, without calling the service")
    void blankZoneIs400() throws Exception {
        mockMvc.perform(withAuth(put(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(" ")))
                .andExpect(status().isBadRequest());

        verify(accountingConfigurationService, never()).setAccountingTimeZone(anyString());
    }

    @Test
    @DisplayName("409 ACCOUNTING_TIME_ZONE_LOCKED once a period was closed")
    void lockedIs409() throws Exception {
        when(accountingConfigurationService.setAccountingTimeZone("America/Chicago"))
                .thenThrow(new AccountingTimeZoneLockedException("UTC", "America/Chicago"));

        mockMvc.perform(withAuth(put(PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("America/Chicago")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNTING_TIME_ZONE_LOCKED"));
    }

    @Test
    @DisplayName("403 without accounting:period:hard_lock")
    void requiresTheHardLockAuthority() throws Exception {
        mockMvc.perform(withAuth(put(PATH), "accounting:period:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("America/Chicago")))
                .andExpect(status().isForbidden());

        verify(accountingConfigurationService, never()).setAccountingTimeZone(anyString());
    }

    // ---- cash safety cushion (#2515) ----------------------------------------------------------------------------

    private static final String CUSHION_PATH = "/v1/accounting/configuration/cash-safety-cushion";
    private static final String CUSHION_BODY = """
            {"amount":2500.00,"currencyCode":"USD","justification":"Treasury sets one payroll",
             "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e"}
            """;

    @Test
    @DisplayName("GET cash-safety-cushion: 200 with a null amount while unset")
    void getsTheUnsetCushion() throws Exception {
        when(accountingConfigurationService.getCashSafetyCushion())
                .thenReturn(
                        CashSafetyCushionResponse.builder().currencyCode("USD").build());

        mockMvc.perform(withAuth(get(CUSHION_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").doesNotExist())
                .andExpect(jsonPath("$.currencyCode").value("USD"));
    }

    @Test
    @DisplayName("PUT cash-safety-cushion: 200 with the stored cushion; the body reaches the service")
    void setsTheCushion() throws Exception {
        when(accountingConfigurationService.setCashSafetyCushion(any(CashSafetyCushionRequest.class)))
                .thenReturn(CashSafetyCushionResponse.builder()
                        .amount(new BigDecimal("2500.00"))
                        .currencyCode("USD")
                        .build());

        mockMvc.perform(withAuth(put(CUSHION_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSHION_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(2500.00))
                .andExpect(jsonPath("$.currencyCode").value("USD"));

        org.mockito.ArgumentCaptor<CashSafetyCushionRequest> sent =
                org.mockito.ArgumentCaptor.forClass(CashSafetyCushionRequest.class);
        verify(accountingConfigurationService).setCashSafetyCushion(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().isAmountPresent())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getAmount()).isEqualByComparingTo("2500.00");
    }

    @Test
    @DisplayName("PUT cash-safety-cushion: a null amount is present (clears); an absent key is not")
    void nullAmountIsPresent() throws Exception {
        when(accountingConfigurationService.setCashSafetyCushion(any(CashSafetyCushionRequest.class)))
                .thenReturn(
                        CashSafetyCushionResponse.builder().currencyCode("USD").build());

        mockMvc.perform(withAuth(put(CUSHION_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"amount":null,"currencyCode":"USD","justification":"Removing the cushion",
                                 "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e"}
                                """))
                .andExpect(status().isOk());
        mockMvc.perform(withAuth(put(CUSHION_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"currencyCode":"USD","justification":"Removing the cushion",
                                 "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6e"}
                                """))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CashSafetyCushionRequest> sent =
                org.mockito.ArgumentCaptor.forClass(CashSafetyCushionRequest.class);
        verify(accountingConfigurationService, org.mockito.Mockito.times(2)).setCashSafetyCushion(sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getAllValues().get(0).isAmountPresent())
                .isTrue();
        org.assertj.core.api.Assertions.assertThat(sent.getAllValues().get(1).isAmountPresent())
                .isFalse();
    }

    @Test
    @DisplayName("PUT cash-safety-cushion: 400 VALIDATION_ERROR for an invalid field")
    void invalidCushionIs400() throws Exception {
        when(accountingConfigurationService.setCashSafetyCushion(any(CashSafetyCushionRequest.class)))
                .thenThrow(InvalidRequestParameterException.forField("amount", "amount must not be negative"));

        mockMvc.perform(withAuth(put(CUSHION_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSHION_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("PUT cash-safety-cushion: 422 CURRENCY_NOT_SUPPORTED for another currency")
    void foreignCurrencyIs422() throws Exception {
        when(accountingConfigurationService.setCashSafetyCushion(any(CashSafetyCushionRequest.class)))
                .thenThrow(new CurrencyNotSupportedException("currencyCode EUR is not the functional currency USD"));

        mockMvc.perform(withAuth(put(CUSHION_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSHION_BODY.replace("USD", "EUR")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CURRENCY_NOT_SUPPORTED"));
    }

    @Test
    @DisplayName("cash-safety-cushion: 403 without accounting:period:hard_lock, for GET and PUT")
    void cushionRequiresTheHardLockAuthority() throws Exception {
        mockMvc.perform(withAuth(get(CUSHION_PATH), "accounting:period:view")).andExpect(status().isForbidden());
        mockMvc.perform(withAuth(put(CUSHION_PATH), "accounting:period:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUSHION_BODY))
                .andExpect(status().isForbidden());

        verify(accountingConfigurationService, never()).setCashSafetyCushion(any());
        verify(accountingConfigurationService, never()).getCashSafetyCushion();
    }

    // ---- aged thresholds (#2515) --------------------------------------------------------------------------------

    private static final String THRESHOLDS_PATH = "/v1/accounting/configuration/aged-thresholds";
    private static final String THRESHOLDS_BODY = """
            {"undepositedCashAgedDays":3,"vendorCodUnappliedAgedDays":14,"deliveriesNotBilledDays":30,
             "justification":"Back to the standard thresholds",
             "requestId":"019a1d3e-6f7a-7b8c-9d0e-1f2a3b4c5d6f"}
            """;

    @Test
    @DisplayName("GET aged-thresholds: 200 with the three effective values")
    void getsTheThresholds() throws Exception {
        when(accountingConfigurationService.getAgedThresholds())
                .thenReturn(AgedThresholdsResponse.builder()
                        .undepositedCashAgedDays(3)
                        .vendorCodUnappliedAgedDays(14)
                        .deliveriesNotBilledDays(30)
                        .build());

        mockMvc.perform(withAuth(get(THRESHOLDS_PATH)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undepositedCashAgedDays").value(3))
                .andExpect(jsonPath("$.vendorCodUnappliedAgedDays").value(14))
                .andExpect(jsonPath("$.deliveriesNotBilledDays").value(30));
    }

    @Test
    @DisplayName("PUT aged-thresholds: 200 with the stored thresholds")
    void setsTheThresholds() throws Exception {
        when(accountingConfigurationService.setAgedThresholds(any(AgedThresholdsRequest.class)))
                .thenReturn(AgedThresholdsResponse.builder()
                        .undepositedCashAgedDays(3)
                        .vendorCodUnappliedAgedDays(14)
                        .deliveriesNotBilledDays(30)
                        .build());

        mockMvc.perform(withAuth(put(THRESHOLDS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLDS_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveriesNotBilledDays").value(30));
    }

    @Test
    @DisplayName("PUT aged-thresholds: 400 VALIDATION_ERROR for a value outside its range")
    void outOfRangeThresholdIs400() throws Exception {
        when(accountingConfigurationService.setAgedThresholds(any(AgedThresholdsRequest.class)))
                .thenThrow(InvalidRequestParameterException.forField(
                        "deliveriesNotBilledDays", "deliveriesNotBilledDays must be between 7 and 120 days"));

        mockMvc.perform(withAuth(put(THRESHOLDS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLDS_BODY.replace(
                                "\"deliveriesNotBilledDays\":30", "\"deliveriesNotBilledDays\":6")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    @DisplayName("PUT aged-thresholds: 409 when the requestId was used with another body")
    void reusedRequestIdIs409() throws Exception {
        when(accountingConfigurationService.setAgedThresholds(any(AgedThresholdsRequest.class)))
                .thenThrow(new IdempotencyConflictException("requestId was already used"));

        mockMvc.perform(withAuth(put(THRESHOLDS_PATH))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLDS_BODY))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("aged-thresholds: 403 without accounting:period:hard_lock, for GET and PUT")
    void thresholdsRequireTheHardLockAuthority() throws Exception {
        mockMvc.perform(withAuth(get(THRESHOLDS_PATH), "accounting:period:view"))
                .andExpect(status().isForbidden());
        mockMvc.perform(withAuth(put(THRESHOLDS_PATH), "accounting:period:view")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(THRESHOLDS_BODY))
                .andExpect(status().isForbidden());

        verify(accountingConfigurationService, never()).setAgedThresholds(any());
        verify(accountingConfigurationService, never()).getAgedThresholds();
    }
}
