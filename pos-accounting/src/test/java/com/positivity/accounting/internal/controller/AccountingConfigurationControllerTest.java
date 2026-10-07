package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.exception.AccountingTimeZoneLockedException;
import com.positivity.accounting.internal.exception.InvalidAccountingTimeZoneException;
import com.positivity.accounting.internal.service.AccountingConfigurationService;
import com.positivity.accounting.internal.service.AccountingPeriodService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("PUT /v1/accounting/configuration/time-zone (#2558)")
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
}
