package com.positivity.accounting.internal.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import com.positivity.accounting.internal.enums.TemplateEntryKind;
import com.positivity.accounting.internal.enums.TemplateEntryReason;
import com.positivity.accounting.internal.enums.TenantTemplateState;
import com.positivity.accounting.internal.service.TenantTemplateService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** #2526: the tenant template status read is permission-gated and takes no tenant from the request. */
@DisplayName("TenantTemplateController status")
class TenantTemplateStatusControllerTest extends BaseIntegrationTest {

    private static final String URL = "/v1/accounting/tenant-template/status";

    @MockitoBean
    private TenantTemplateService tenantTemplateService;

    @Test
    @DisplayName("200 with state, counts and attention in business words when the caller holds accounting:coa:view")
    void status_returns200_withAuthority() throws Exception {
        when(tenantTemplateService.status())
                .thenReturn(new TenantTemplateStatusResponse(
                        TenantTemplateState.NEEDS_ATTENTION,
                        Instant.parse("2026-10-05T14:03:22Z"),
                        new TenantTemplateStatusResponse.Counts(180, 2, 0, 1, 1),
                        false,
                        List.of(new TenantTemplateStatusResponse.AttentionItem(
                                "ACCOUNT:6295",
                                TemplateEntryKind.ACCOUNT,
                                TemplateEntryReason.ACCOUNT_DIFFERS,
                                "6295 Staff Meals & Refreshments, expense",
                                "6295 Tire disposal, expense"))));

        mockMvc.perform(get(URL).header("X-Authorities", "accounting:coa:view").header("X-User", "support"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NEEDS_ATTENTION"))
                .andExpect(jsonPath("$.lastAppliedAt").value("2026-10-05T14:03:22Z"))
                .andExpect(jsonPath("$.counts.created").value(180))
                .andExpect(jsonPath("$.counts.conflict").value(1))
                .andExpect(jsonPath("$.retreadPlantAddOn").value(false))
                .andExpect(jsonPath("$.attention[0].entryKey").value("ACCOUNT:6295"))
                .andExpect(jsonPath("$.attention[0].reason").value("ACCOUNT_DIFFERS"))
                .andExpect(jsonPath("$.attention[0].templateValue").value("6295 Staff Meals & Refreshments, expense"))
                .andExpect(jsonPath("$.attention[0].tenantValue").value("6295 Tire disposal, expense"));
    }

    @Test
    @DisplayName("a tenant id in the request is not read: the service is asked for the caller's own tenant")
    void status_takesNoTenantFromTheRequest() throws Exception {
        when(tenantTemplateService.status())
                .thenReturn(new TenantTemplateStatusResponse(
                        TenantTemplateState.UP_TO_DATE,
                        null,
                        new TenantTemplateStatusResponse.Counts(0, 0, 0, 0, 0),
                        false,
                        List.of()));

        mockMvc.perform(get(URL).param("tenantId", "01990000-0000-7000-8000-0000000000b2")
                        .header("X-Authorities", "accounting:coa:view")
                        .header("X-User", "support"))
                .andExpect(status().isOk());

        verify(tenantTemplateService).status();
    }

    @Test
    @DisplayName("403 without accounting:coa:view")
    void status_returns403_withoutAuthority() throws Exception {
        mockMvc.perform(get(URL).header("X-Authorities", "accounting:coa:create")
                        .header("X-User", "clerk"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(tenantTemplateService);
    }
}
