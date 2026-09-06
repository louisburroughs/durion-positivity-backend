package com.positivity.supplier.internal.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.supplier.internal.config.SecurityConfig;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.mktcat.service.MktCatImporter;
import com.positivity.supplier.internal.mktcat.service.MktCatStagedReader;
import com.positivity.supplier.internal.mktcat.service.model.MarketingEnrichmentView;
import com.positivity.supplier.internal.security.SupplierPermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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
 * Web contract of the staged marketing catalogue read (CAP-324 #1230), over the real production
 * {@link SecurityConfig}.
 *
 * <p>The assertion this class exists for is the {@code hasUnresolvedImages} filter added for #1645
 * (issue #1638 decision 4): omitted it lists everything, {@code true}/{@code false} narrows to the
 * supplier-scoped staged rows, and the filter is passed through to
 * {@link MktCatStagedReader#findStaged(SupplierRef, int, Boolean)} exactly as given.
 */
@WebMvcTest(controllers = SupplierMarketingCatalogController.class)
@Import({SecurityConfig.class, SupplierMarketingCatalogControllerWebMvcTest.FixedClockConfig.class})
@DisplayName("Staged marketing catalogue web contract (#1645)")
class SupplierMarketingCatalogControllerWebMvcTest {

    /** The exception advice needs a Clock, and the gateway filter must run inside the security chain. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-08-16T10:00:00Z"), ZoneOffset.UTC);
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

    private static final String SUPPLIER_REF = "ediwheel-net";
    private static final String BASE = "/v1/supplier/mktcat/" + SUPPLIER_REF + "/variants";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MktCatImporter importer;

    @MockitoBean
    private MktCatStagedReader stagedReader;

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder, String... authorities) {
        return builder.header("X-User", "web-mvc-tester").header("X-Authorities", String.join(",", authorities));
    }

    private static MarketingEnrichmentView variant(String vendorVariantId, boolean unresolvedImages) {
        return new MarketingEnrichmentView(
                vendorVariantId,
                SUPPLIER_REF,
                "Michelin",
                "Pilot Sport 4S",
                "Pilot Sport 4S 225/45R17",
                "SUMMER",
                unresolvedImages,
                Instant.parse("2026-08-01T00:00:00Z"),
                Instant.parse("2026-08-16T10:00:00Z"),
                null);
    }

    @Test
    void withNoFilterListsEveryStagedVariant() throws Exception {
        when(stagedReader.findStaged(eq(new SupplierRef(SUPPLIER_REF)), eq(100), isNull()))
                .thenReturn(List.of(variant("v1", true), variant("v2", false)));

        mockMvc.perform(authed(get(BASE), SupplierPermissions.MARKETING_CATALOG_IMPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].vendorVariantId").value("v1"))
                .andExpect(jsonPath("$[1].vendorVariantId").value("v2"));
    }

    @Test
    void hasUnresolvedImagesTrueFiltersToVariantsStillMissingArtwork() throws Exception {
        when(stagedReader.findStaged(eq(new SupplierRef(SUPPLIER_REF)), eq(100), eq(Boolean.TRUE)))
                .thenReturn(List.of(variant("v1", true)));

        mockMvc.perform(authed(get(BASE + "?hasUnresolvedImages=true"), SupplierPermissions.MARKETING_CATALOG_IMPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].vendorVariantId").value("v1"))
                .andExpect(jsonPath("$[0].unresolvedImages").value(true));
    }

    @Test
    void hasUnresolvedImagesFalseFiltersToVariantsWithResolvedArtwork() throws Exception {
        when(stagedReader.findStaged(eq(new SupplierRef(SUPPLIER_REF)), eq(100), eq(Boolean.FALSE)))
                .thenReturn(List.of(variant("v2", false)));

        mockMvc.perform(authed(get(BASE + "?hasUnresolvedImages=false"), SupplierPermissions.MARKETING_CATALOG_IMPORT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].vendorVariantId").value("v2"))
                .andExpect(jsonPath("$[0].unresolvedImages").value(false));
    }

    @Test
    void isRejectedWithoutAuthentication() throws Exception {
        mockMvc.perform(get(BASE)).andExpect(status().isUnauthorized());
    }

    @Test
    void isDeniedWithoutTheImportAuthority() throws Exception {
        mockMvc.perform(authed(get(BASE), SupplierPermissions.PROFILE_READ)).andExpect(status().isForbidden());
    }
}
