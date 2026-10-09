package com.positivity.accounting.internal.controller;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.InformationReturnFormsResponse;
import com.positivity.accounting.internal.exception.TaxServiceUnavailableException;
import com.positivity.accounting.internal.service.InformationReturnFormsService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** The information-return forms front door (#2615): 200 relayed, 403 without accounting:ap:view, 503 with Retry-After. */
@DisplayName("GET /v1/accounting/information-return-forms (#2615)")
class InformationReturnFormsControllerTest extends BaseIntegrationTest {

    private static final String URL = "/v1/accounting/information-return-forms";

    @MockitoBean
    private InformationReturnFormsService informationReturnForms;

    @Test
    @DisplayName("200: the tax country's forms, boxes and payee-id schemes, source STUB")
    void relaysTheForms() throws Exception {
        when(informationReturnForms.forms())
                .thenReturn(new InformationReturnFormsResponse(
                        "ZZ",
                        "STUB",
                        List.of(new InformationReturnFormsResponse.Form(
                                "ZZ_FORM_A",
                                "Form A",
                                List.of(new InformationReturnFormsResponse.Box("1", "Box one")),
                                List.of("ZZ_BUSINESS_ID")))));

        mockMvc.perform(withAuth(get(URL), "accounting:ap:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countryCode").value("ZZ"))
                .andExpect(jsonPath("$.source").value("STUB"))
                .andExpect(jsonPath("$.forms[0].form").value("ZZ_FORM_A"))
                .andExpect(jsonPath("$.forms[0].boxes[0].box").value("1"))
                .andExpect(jsonPath("$.forms[0].boxes[0].label").value("Box one"))
                .andExpect(jsonPath("$.forms[0].payeeIdSchemes[0]").value("ZZ_BUSINESS_ID"));
    }

    @Test
    @DisplayName("403 without accounting:ap:view")
    void needsApView() throws Exception {
        mockMvc.perform(withAuth(get(URL), "accounting:je:view")).andExpect(status().isForbidden());
        verify(informationReturnForms, never()).forms();
    }

    @Test
    @DisplayName("AC10: pos-tax unreachable is 503 SERVICE_UNAVAILABLE with Retry-After")
    void posTaxDown() throws Exception {
        when(informationReturnForms.forms())
                .thenThrow(new TaxServiceUnavailableException("The tax service is unavailable"));

        mockMvc.perform(withAuth(get(URL), "accounting:ap:view"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.code").value("SERVICE_UNAVAILABLE"));
    }
}
