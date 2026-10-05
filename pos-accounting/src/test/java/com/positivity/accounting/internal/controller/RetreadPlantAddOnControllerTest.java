package com.positivity.accounting.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.service.TenantTemplateService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** #2526 (AW30): turning the retread-plant add-on on is permission-gated and validated. */
@DisplayName("TenantTemplateController retread-plant add-on")
class RetreadPlantAddOnControllerTest extends BaseIntegrationTest {

    private static final String URL = "/v1/accounting/tenant-template/add-ons/retread-plant";
    private static final String REQUEST_ID = "019a0000-0000-7000-8000-000000000009";

    @MockitoBean
    private TenantTemplateService tenantTemplateService;

    @Test
    @DisplayName("200 with the tenant's status when the caller holds accounting:coa:create")
    void enable_returns200_withAuthority() throws Exception {
        when(tenantTemplateService.enableRetreadPlantAddOn(any()))
                .thenReturn(new TenantTemplateStatusResponse(
                        TenantTemplateState.UP_TO_DATE,
                        Instant.parse("2026-10-05T14:03:22Z"),
                        new TenantTemplateStatusResponse.Counts(198, 0, 0, 0, 0),
                        true,
                        List.of()));

        mockMvc.perform(put(URL).header("X-Authorities", "accounting:coa:create")
                        .header("X-User", "carol.controller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"justification":"We run a retread plant at the Tulsa shop","requestId":"%s"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("UP_TO_DATE"))
                .andExpect(jsonPath("$.retreadPlantAddOn").value(true))
                .andExpect(jsonPath("$.counts.created").value(198));

        verify(tenantTemplateService)
                .enableRetreadPlantAddOn(new EnableTemplateAddOnRequest(
                        "We run a retread plant at the Tulsa shop", UUID.fromString(REQUEST_ID)));
    }

    @Test
    @DisplayName("403 without accounting:coa:create, even with accounting:coa:view")
    void enable_returns403_withoutAuthority() throws Exception {
        mockMvc.perform(put(URL).header("X-Authorities", "accounting:coa:view")
                        .header("X-User", "clerk")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"justification":"We run a retread plant at the Tulsa shop","requestId":"%s"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tenantTemplateService);
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR for a justification under 10 characters or a missing requestId")
    void enable_returns400_forInvalidBody() throws Exception {
        mockMvc.perform(put(URL).header("X-Authorities", "accounting:coa:create")
                        .header("X-User", "carol.controller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"justification":"too short","requestId":"%s"}
                                """.formatted(REQUEST_ID)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(put(URL).header("X-Authorities", "accounting:coa:create")
                        .header("X-User", "carol.controller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"justification\":\"We run a retread plant at the Tulsa shop\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verifyNoInteractions(tenantTemplateService);
    }
}
