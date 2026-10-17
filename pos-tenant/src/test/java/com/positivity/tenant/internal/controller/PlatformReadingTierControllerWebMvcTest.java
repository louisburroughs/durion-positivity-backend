package com.positivity.tenant.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.tenant.internal.config.SecurityConfig;
import com.positivity.tenant.internal.config.TenantApiExceptionHandler;
import com.positivity.tenant.internal.dto.MonthlyAllowanceDto;
import com.positivity.tenant.internal.dto.ReadingTierResponse;
import com.positivity.tenant.internal.exception.ReadingTierExistsException;
import com.positivity.tenant.internal.exception.ReadingTierInUseException;
import com.positivity.tenant.internal.service.ReadingTierService;
import com.positivity.web.common.WebCommonErrorAutoConfiguration;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(PlatformReadingTierController.class)
@Import({
    SecurityConfig.class,
    WebCommonErrorAutoConfiguration.class,
    TenantApiExceptionHandler.class,
    PlatformReadingTierControllerWebMvcTest.SliceConfig.class
})
@DisplayName("/v1/platform/reading-tiers (#2619): permissions, validation, the named codes")
class PlatformReadingTierControllerWebMvcTest {

    @TestConfiguration
    static class SliceConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-10-17T12:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ReadingTierService readingTiers;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "platform-admin").header("X-Authorities", String.join(",", authorities));
    }

    private static ReadingTierResponse tier(String code, String amount, boolean active) {
        return ReadingTierResponse.builder()
                .id(UUID.randomUUID())
                .code(code)
                .displayName(code)
                .monthlyAllowance(new MonthlyAllowanceDto(new BigDecimal(amount), "USD"))
                .active(active)
                .version(0L)
                .build();
    }

    @Test
    @DisplayName("list needs platform:reading_tier:read; create and update need platform:reading_tier:manage")
    void permissions() throws Exception {
        mockMvc.perform(get("/v1/platform/reading-tiers")).andExpect(status().isUnauthorized());
        mockMvc.perform(authed(get("/v1/platform/reading-tiers"), "platform:tenant:read"))
                .andExpect(status().isForbidden());
        when(readingTiers.list()).thenReturn(List.of(tier("STANDARD", "20.00", true)));
        mockMvc.perform(authed(get("/v1/platform/reading-tiers"), "platform:reading_tier:read"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].code").value("STANDARD"))
                .andExpect(jsonPath("$[0].monthlyAllowance.amount").value(20.00));

        String body = "{\"code\":\"PRO\",\"displayName\":\"Pro\",\"monthlyAllowance\":{\"amount\":60.00,\"currencyCode\":\"USD\"}}";
        mockMvc.perform(authed(post("/v1/platform/reading-tiers"), "platform:reading_tier:read")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        verify(readingTiers, never()).create(any());
        when(readingTiers.create(any())).thenReturn(tier("PRO", "60.00", true));
        mockMvc.perform(authed(post("/v1/platform/reading-tiers"), "platform:reading_tier:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("PRO"));
    }

    @Test
    @DisplayName("400 VALIDATION_ERROR for a lower-case code, a negative amount or a bad currency")
    void validation() throws Exception {
        for (String body : List.of(
                "{\"code\":\"pro\",\"displayName\":\"Pro\",\"monthlyAllowance\":{\"amount\":60.00,\"currencyCode\":\"USD\"}}",
                "{\"code\":\"PRO\",\"displayName\":\"Pro\",\"monthlyAllowance\":{\"amount\":-1,\"currencyCode\":\"USD\"}}",
                "{\"code\":\"PRO\",\"displayName\":\"Pro\",\"monthlyAllowance\":{\"amount\":1,\"currencyCode\":\"usd\"}}",
                "{\"code\":\"PRO\",\"displayName\":\"Pro\"}")) {
            mockMvc.perform(authed(post("/v1/platform/reading-tiers"), "platform:reading_tier:manage")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verify(readingTiers, never()).create(any());
    }

    @Test
    @DisplayName("409 READING_TIER_EXISTS and 409 READING_TIER_IN_USE are named, with a correlation id")
    void namedCodes() throws Exception {
        when(readingTiers.create(any())).thenThrow(new ReadingTierExistsException("Reading tier already exists: PRO"));
        mockMvc.perform(authed(post("/v1/platform/reading-tiers"), "platform:reading_tier:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"PRO\",\"displayName\":\"Pro\",\"monthlyAllowance\":{\"amount\":60.00,\"currencyCode\":\"USD\"}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_TIER_EXISTS"))
                .andExpect(header().exists("X-Correlation-Id"));

        when(readingTiers.update(eq("STANDARD"), any()))
                .thenThrow(new ReadingTierInUseException("Reading tier STANDARD is assigned"));
        mockMvc.perform(authed(patch("/v1/platform/reading-tiers/{code}", "STANDARD"), "platform:reading_tier:manage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}")
                        .header("X-Correlation-Id", "corr-2619"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_TIER_IN_USE"))
                .andExpect(jsonPath("$.correlationId").value("corr-2619"))
                .andExpect(header().string("X-Correlation-Id", "corr-2619"));
    }
}
