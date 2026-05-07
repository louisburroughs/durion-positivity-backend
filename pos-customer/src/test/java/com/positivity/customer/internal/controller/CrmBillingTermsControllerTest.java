package com.positivity.customer.internal.controller;

import static org.mockito.Mockito.lenient;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.customer.config.WebMvcTestSecurityConfig;
import com.positivity.customer.internal.config.CrmExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CrmBillingTermsController.class)
@Import({WebMvcTestSecurityConfig.class, CrmExceptionHandler.class})
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CrmBillingTermsControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
        lenient().when(clock.getZone()).thenReturn(java.time.ZoneOffset.UTC);
    }

    // ─── GET /v1/crm/billing-terms — 200 OK ──────────────────────────────────

    @Test
    void listBillingTerms_withPartyViewAuthority_returns200WithAllTerms() throws Exception {
        mockMvc.perform(get("/v1/crm/billing-terms").header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andExpect(jsonPath("$[0].code").value("NET_30"))
                .andExpect(jsonPath("$[0].label").value("Net 30"))
                .andExpect(jsonPath("$[0].netDays").value(30));
    }

    @Test
    void listBillingTerms_includesCodAndPrepaidTerms() throws Exception {
        mockMvc.perform(get("/v1/crm/billing-terms").header("X-Authorities", "crm:party:view"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[3].code").value("COD"))
                .andExpect(jsonPath("$[3].netDays").value(0))
                .andExpect(jsonPath("$[4].code").value("PREPAID"))
                .andExpect(jsonPath("$[4].netDays").value(-1));
    }

    // ─── GET /v1/crm/billing-terms — 403 Forbidden ───────────────────────────

    @Test
    void listBillingTerms_withoutPartyViewAuthority_returns403() throws Exception {
        mockMvc.perform(get("/v1/crm/billing-terms").header("X-Authorities", "crm:party:create"))
                .andExpect(status().isForbidden());
    }
}
