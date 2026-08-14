package com.positivity.supplier.internal.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.service.SupplierPriceCatalogService;
import com.positivity.supplier.service.model.PagedResponse;
import com.positivity.supplier.service.model.PriceCatalogImportSummary;
import com.positivity.supplier.service.model.UnmatchedPriceCatalogLineView;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Web contract of the PRICAT admin API (#1224), over the real production {@link SecurityConfig}.
 *
 * <p>The assertion this class exists for is the permission split: reading import bookkeeping and
 * triggering a vendor fetch are different authorities, and holding one must not grant the other.
 * Every endpoint is therefore exercised granted, denied-with-the-other-supplier-authority, and
 * unauthenticated.
 */
@WebMvcTest(controllers = SupplierPriceCatalogAdminController.class)
@Import({SecurityConfig.class, SupplierPriceCatalogAdminControllerWebMvcTest.FixedClockConfig.class})
@DisplayName("PRICAT admin API web contract (#1224)")
class SupplierPriceCatalogAdminControllerWebMvcTest {

    /** The exception advice needs a Clock, and the gateway filter must run inside the security chain. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneOffset.UTC);
        }

        @Bean
        org.springframework.boot.web.servlet.FilterRegistrationBean<
                        com.positivity.security.common.GatewayAuthoritiesFilter>
                gatewayAuthoritiesFilterRegistration(
                        com.positivity.security.common.GatewayAuthoritiesFilter gatewayAuthoritiesFilter) {
            var registration =
                    new org.springframework.boot.web.servlet.FilterRegistrationBean<>(gatewayAuthoritiesFilter);
            registration.setEnabled(false);
            return registration;
        }
    }

    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final String BASE = "/v1/supplier/admin/price-catalog/" + PROFILE_ID;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SupplierPriceCatalogService priceCatalogService;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static PriceCatalogImportSummary summary(String status) {
        return new PriceCatalogImportSummary(
                MANIFEST_ID,
                PROFILE_ID,
                "michelin-eu",
                status,
                Instant.parse("2026-08-13T09:00:00Z"),
                Instant.parse("2026-08-13T09:05:00Z"),
                1200,
                1180,
                18,
                2,
                3,
                "PRICAT-4046266",
                LocalDate.of(2026, 8, 13),
                "SE",
                "SEK",
                null);
    }

    private static UnmatchedPriceCatalogLineView unmatchedLine() {
        return new UnmatchedPriceCatalogLineView(
                UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d"),
                MANIFEST_ID,
                PROFILE_ID,
                417,
                "3528709999083",
                "999908",
                null,
                "NO_CATALOG_MATCH",
                "no catalog product carries the line's identifiers",
                null,
                null,
                LocalDate.of(2026, 2, 1),
                "SEK",
                Instant.parse("2026-08-13T09:00:00Z"),
                null);
    }

    @Nested
    @DisplayName("POST /imports")
    class TriggerImport {

        @Test
        void returnsTheTerminalSummaryToAHolderOfTheImportAuthority() throws Exception {
            when(priceCatalogService.runImport(PROFILE_ID)).thenReturn(summary("COMPLETED"));

            mockMvc.perform(authed(post(BASE + "/imports"), SupplierPermissions.PRICECATALOG_IMPORT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.importManifestId").value(MANIFEST_ID.toString()))
                    .andExpect(jsonPath("$.status").value("COMPLETED"))
                    .andExpect(jsonPath("$.linesUnmatched").value(18));
        }

        @Test
        void reportsAFailedFetchAsASummaryRatherThanAnError() throws Exception {
            when(priceCatalogService.runImport(PROFILE_ID)).thenReturn(summary("FAILED"));

            mockMvc.perform(authed(post(BASE + "/imports"), SupplierPermissions.PRICECATALOG_IMPORT))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("FAILED"));
        }

        @Test
        void isDeniedToAReaderWhoCannotTrigger() throws Exception {
            mockMvc.perform(authed(post(BASE + "/imports"), SupplierPermissions.PRICECATALOG_READ))
                    .andExpect(status().isForbidden());

            verify(priceCatalogService, never()).runImport(PROFILE_ID);
        }

        @Test
        void isDeniedToAProfileAdministrator() throws Exception {
            mockMvc.perform(authed(post(BASE + "/imports"), SupplierPermissions.PROFILE_WRITE))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(post(BASE + "/imports")).andExpect(status().isUnauthorized());
        }

        @Test
        void reportsAnUnconfiguredCapabilityAsAConflict() throws Exception {
            when(priceCatalogService.runImport(PROFILE_ID))
                    .thenThrow(new SupplierConfigurationException(
                            SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                            "PRICE_CATALOG has no endpoint binding"));

            mockMvc.perform(authed(post(BASE + "/imports"), SupplierPermissions.PRICECATALOG_IMPORT))
                    .andExpect(status().isConflict());
        }
    }

    @Nested
    @DisplayName("GET /imports")
    class ListImports {

        @Test
        void returnsAPageToAReader() throws Exception {
            when(priceCatalogService.listImports(eq(PROFILE_ID), anyInt(), anyInt()))
                    .thenReturn(new PagedResponse<>(List.of(summary("COMPLETED")), 0, 50, 1, 1));

            mockMvc.perform(authed(get(BASE + "/imports"), SupplierPermissions.PRICECATALOG_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].supplierRef").value("michelin-eu"))
                    .andExpect(jsonPath("$.totalElements").value(1));
        }

        @Test
        void rejectsAPageSizeBeyondTheBound() throws Exception {
            mockMvc.perform(authed(get(BASE + "/imports?size=500"), SupplierPermissions.PRICECATALOG_READ))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void isDeniedToAProfileAdministrator() throws Exception {
            mockMvc.perform(authed(get(BASE + "/imports"), SupplierPermissions.PROFILE_READ))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(get(BASE + "/imports")).andExpect(status().isUnauthorized());
        }
    }

    @Nested
    @DisplayName("GET /unmatched-lines")
    class ListUnmatchedLines {

        @Test
        void returnsTheOpenQuarantineToAReader() throws Exception {
            when(priceCatalogService.listUnmatchedLines(eq(PROFILE_ID), anyInt(), anyInt()))
                    .thenReturn(new PagedResponse<>(List.of(unmatchedLine()), 0, 50, 1, 1));

            mockMvc.perform(authed(get(BASE + "/unmatched-lines"), SupplierPermissions.PRICECATALOG_READ))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[0].reason").value("NO_CATALOG_MATCH"))
                    .andExpect(jsonPath("$.items[0].articleEan").value("3528709999083"));
        }

        @Test
        void isDeniedWithoutThePriceCatalogReadAuthority() throws Exception {
            mockMvc.perform(authed(get(BASE + "/unmatched-lines"), SupplierPermissions.AUDIT_READ))
                    .andExpect(status().isForbidden());
        }

        @Test
        void isRejectedWithoutAuthentication() throws Exception {
            mockMvc.perform(get(BASE + "/unmatched-lines")).andExpect(status().isUnauthorized());
        }
    }
}
