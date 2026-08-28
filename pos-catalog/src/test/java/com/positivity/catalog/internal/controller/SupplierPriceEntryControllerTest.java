package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.SupplierPriceEntryDto;
import com.positivity.catalog.internal.dto.SupplierPriceImportStatusDto;
import com.positivity.catalog.internal.service.SupplierPriceEntryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(SupplierPriceEntryController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("GET /v1/catalog/supplier-prices (#1308)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class SupplierPriceEntryControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");
    private static final UUID PROFILE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID MANIFEST_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final String BASE = "/v1/catalog/supplier-prices";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    SupplierPriceEntryService supplierPriceEntryService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    private static SupplierPriceEntryDto entry() {
        return new SupplierPriceEntryDto(
                UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5e"),
                PROFILE_ID,
                "michelin-eu",
                PRODUCT_ID,
                "EAN",
                "3528709999083",
                "999908",
                "30012456",
                "SE",
                "SEK",
                new BigDecimal("3195.0000"),
                new BigDecimal("2895.5000"),
                new BigDecimal("2450.2500"),
                new BigDecimal("25.0000"),
                new BigDecimal("35.0000"),
                LocalDate.of(2026, 2, 1),
                "PRICAT-1",
                LocalDate.of(2026, 1, 30),
                MANIFEST_ID,
                Instant.parse("2026-08-14T08:00:00Z"));
    }

    @Test
    void returnsTheApplicableVendorPrice() throws Exception {
        when(supplierPriceEntryService.findLatest(eq(PRODUCT_ID), isNull(), eq("SE"), eq("SEK"), isNull()))
                .thenReturn(Optional.of(entry()));

        mockMvc.perform(get(BASE + "/latest")
                        .param("productId", PRODUCT_ID.toString())
                        .param("countryCode", "SE")
                        .param("currency", "SEK"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.netPrice").value(2450.25))
                .andExpect(jsonPath("$.effectiveFrom").value("2026-02-01"))
                .andExpect(jsonPath("$.sourceDocumentId").value("PRICAT-1"));
    }

    @Test
    void returns404WhenNoVendorPriceApplies() throws Exception {
        when(supplierPriceEntryService.findLatest(any(), any(), any(), any(), any()))
                .thenReturn(Optional.empty());

        mockMvc.perform(get(BASE + "/latest")
                        .param("productId", PRODUCT_ID.toString())
                        .param("countryCode", "SE")
                        .param("currency", "SEK"))
                .andExpect(status().isNotFound());
    }

    @Test
    void passesTheRequestedDayThrough() throws Exception {
        when(supplierPriceEntryService.findLatest(
                        eq(PRODUCT_ID), eq(PROFILE_ID), eq("SE"), eq("SEK"), eq(LocalDate.of(2026, 3, 1))))
                .thenReturn(Optional.of(entry()));

        mockMvc.perform(get(BASE + "/latest")
                        .param("productId", PRODUCT_ID.toString())
                        .param("countryCode", "SE")
                        .param("currency", "SEK")
                        .param("vendorProfileId", PROFILE_ID.toString())
                        .param("asOf", "2026-03-01"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsAMissingRequiredParameter() throws Exception {
        mockMvc.perform(get(BASE + "/latest").param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnsHistoryIncludingSupersededEntries() throws Exception {
        when(supplierPriceEntryService.findHistory(eq(PRODUCT_ID), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(entry(), entry())));

        mockMvc.perform(get(BASE + "/history").param("productId", PRODUCT_ID.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void rejectsAnOversizedHistoryPage() throws Exception {
        mockMvc.perform(get(BASE + "/history")
                        .param("productId", PRODUCT_ID.toString())
                        .param("size", "500"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void listsImportsThatCouldNotBeConfirmedComplete() throws Exception {
        when(supplierPriceEntryService.findIncompleteImports())
                .thenReturn(List.of(new SupplierPriceImportStatusDto(
                        MANIFEST_ID,
                        PROFILE_ID,
                        "michelin-eu",
                        "INCOMPLETE",
                        1,
                        3,
                        500,
                        1500,
                        Instant.parse("2026-08-14T09:05:00Z"),
                        Instant.parse("2026-08-14T09:05:00Z"))));

        mockMvc.perform(get(BASE + "/imports/incomplete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("INCOMPLETE"))
                .andExpect(jsonPath("$[0].chunksApplied").value(1))
                .andExpect(jsonPath("$[0].expectedChunkCount").value(3));
    }
}
