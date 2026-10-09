package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.TaxRegimesResponse;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.service.TaxRegimesService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The configured tax regimes front door (#2659): 200 relayed for the tax country or a given country, 400 for a
 * malformed country, 403 without accounting:tax_registration:view, 503 with Retry-After.
 */
@DisplayName("GET /v1/accounting/tax-regimes (#2659)")
class TaxRegimesControllerTest extends BaseIntegrationTest {

    private static final String URL = "/v1/accounting/tax-regimes";
    private static final String VIEW = "accounting:tax_registration:view";

    @MockitoBean
    private TaxRegimesService taxRegimes;

    private static TaxRegimesResponse regimes(String country) {
        return new TaxRegimesResponse(
                country,
                "STUB",
                List.of(new TaxRegimesResponse.Regime(
                        "ZZ_REGIME_1", List.of("R1"), List.of(new TaxRegimesResponse.TaxType("ZZ_LEVY", "COUNTRY")))));
    }

    @Test
    @DisplayName("200 without countryCode: the tax country's regimes, regions and tax types, source STUB")
    void taxCountry() throws Exception {
        when(taxRegimes.regimes(null)).thenReturn(regimes("ZZ"));

        mockMvc.perform(withAuth(get(URL), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("ZZ"))
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.regimes[0].regime").value("ZZ_REGIME_1"))
                .andExpect(jsonPath("$.regimes[0].regions[0]").value("R1"))
                .andExpect(jsonPath("$.regimes[0].taxTypes[0].taxType").value("ZZ_LEVY"))
                .andExpect(jsonPath("$.regimes[0].taxTypes[0].jurisdictionType").value("COUNTRY"));
    }

    @Test
    @DisplayName("200 with countryCode: that country's regimes")
    void givenCountry() throws Exception {
        when(taxRegimes.regimes("QZ")).thenReturn(regimes("QZ"));

        mockMvc.perform(withAuth(get(URL).param("countryCode", "QZ"), VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("QZ"));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR for a countryCode that is not two upper-case letters; pos-tax is not called")
    void malformedCountry() throws Exception {
        for (String bad : new String[] {"zz", "ZZZ", "Z", "1Z"}) {
            mockMvc.perform(withAuth(get(URL).param("countryCode", bad), VIEW))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(taxRegimes, never()).regimes(any());
    }

    @Test
    @DisplayName("403 without accounting:tax_registration:view")
    void needsTaxRegistrationView() throws Exception {
        mockMvc.perform(withAuth(get(URL), "accounting:ap:view")).andExpect(status().isForbidden());
        verify(taxRegimes, never()).regimes(any());
    }

    @Test
    @DisplayName("503 SERVICE_UNAVAILABLE with Retry-After when pos-tax cannot answer")
    void posTaxDown() throws Exception {
        when(taxRegimes.regimes(null)).thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));

        mockMvc.perform(withAuth(get(URL), VIEW))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
