package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.service.ServiceLaborTimeService;
import com.positivity.catalog.service.model.LaborTimeQuoteResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(LaborTimeResolveController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("POST /v1/catalog/labor-times/resolve (#1569)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class LaborTimeResolveControllerTest {

    private static final String RESOLVE = "/v1/catalog/labor-times/resolve";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    ServiceLaborTimeService serviceLaborTimeService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    @Test
    @DisplayName("a well-formed request answers 200 with the resolved time and provenance")
    void resolvedAnswer() throws Exception {
        when(serviceLaborTimeService.resolveLaborTime(any()))
                .thenReturn(new LaborTimeQuoteResponse(
                        LaborTimeQuoteResponse.Status.RESOLVED,
                        new BigDecimal("1.5"),
                        "RETAIL_FLAT_RATE",
                        "MOCKGUIDE",
                        "2026-09-01",
                        LaborTimeQuoteResponse.MatchGrade.EXACT,
                        "WHEEL-OFF",
                        List.of("BRAKE-PAD-FRONT")));

        mockMvc.perform(post(RESOLVE).contentType(MediaType.APPLICATION_JSON).content("""
                                {"serviceId":"56b14899-cb6c-7628-0763-4c603ec0a325",
                                 "vehicleYear":"2019-2023","make":"Honda","model":"Civic",
                                 "preferredTimeType":"RETAIL_FLAT_RATE"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.laborHours").value(1.5))
                .andExpect(jsonPath("$.sourceCode").value("MOCKGUIDE"))
                .andExpect(jsonPath("$.sourceRevision").value("2026-09-01"))
                .andExpect(jsonPath("$.matchGrade").value("EXACT"))
                .andExpect(jsonPath("$.overlapGroup").value("WHEEL-OFF"))
                .andExpect(jsonPath("$.includedOpCodes[0]").value("BRAKE-PAD-FRONT"));
    }

    @Test
    @DisplayName("a typed miss is still 200 — degradation is a status, never an error")
    void typedMissIs200() throws Exception {
        when(serviceLaborTimeService.resolveLaborTime(any()))
                .thenReturn(LaborTimeQuoteResponse.miss(LaborTimeQuoteResponse.Status.NO_TIME_AVAILABLE));

        mockMvc.perform(post(RESOLVE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"serviceId\":\"56b14899-cb6c-7628-0763-4c603ec0a325\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_TIME_AVAILABLE"));
    }

    @Test
    @DisplayName("a body without serviceId is 400 — the one required field")
    void missingServiceIdIs400() throws Exception {
        mockMvc.perform(post(RESOLVE).contentType(MediaType.APPLICATION_JSON).content("{\"make\":\"Honda\"}"))
                .andExpect(status().isBadRequest());
    }
}
