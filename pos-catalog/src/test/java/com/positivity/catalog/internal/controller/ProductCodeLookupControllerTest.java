package com.positivity.catalog.internal.controller;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.config.ProductFactReplayService;
import com.positivity.catalog.internal.dto.ProductCodeKind;
import com.positivity.catalog.internal.dto.ProductCodeMatch;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.service.CatalogService;
import com.positivity.catalog.internal.service.LocationPriceOverrideService;
import com.positivity.catalog.internal.service.ProductCodeLookupService;
import com.positivity.catalog.internal.service.ProductDetailService;
import com.positivity.catalog.internal.service.ProductLifecycleService;
import com.positivity.catalog.internal.service.ProductMasterDataService;
import com.positivity.catalog.internal.service.ProductSearchService;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ProductController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("GET /v1/products/by-code (#1232)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class ProductCodeLookupControllerTest {

    private static final UUID PRODUCT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final String EAN = "3286340123456";

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    ProductCodeLookupService productCodeLookupService;

    @MockitoBean
    CatalogService catalogService;

    @MockitoBean
    ProductDetailService productDetailService;

    @MockitoBean
    ProductLifecycleService productLifecycleService;

    @MockitoBean
    LocationPriceOverrideService locationPriceOverrideService;

    @MockitoBean
    ProductMasterDataService productMasterDataService;

    @MockitoBean
    ProductSearchService productSearchService;

    @MockitoBean
    ProductFactReplayService productFactReplayService;

    @BeforeEach
    void setUpClock() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    @Test
    void returns200WithTheMatchedProduct() throws Exception {
        when(productCodeLookupService.findByProductCode(ProductCodeKind.EAN, EAN))
                .thenReturn(Optional.of(new ProductCodeMatch(
                        PRODUCT_ID, "SKU-1", "Tire 205/55R16", ProductCodeKind.EAN, EAN, null, "MPN-1")));

        mockMvc.perform(get("/v1/products/by-code").param("codeType", "EAN").param("code", EAN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(PRODUCT_ID.toString()))
                .andExpect(jsonPath("$.codeType").value("EAN"))
                .andExpect(jsonPath("$.code").value(EAN));
    }

    @Test
    void returns404WhenNoProductCarriesTheCode() throws Exception {
        when(productCodeLookupService.findByProductCode(ProductCodeKind.UPC, "0123456789012"))
                .thenReturn(Optional.empty());

        mockMvc.perform(get("/v1/products/by-code").param("codeType", "UPC").param("code", "0123456789012"))
                .andExpect(status().isNotFound());
    }

    @Test
    void returns409WhenTheCodeIsAmbiguous() throws Exception {
        when(productCodeLookupService.findByProductCode(ProductCodeKind.EAN, EAN))
                .thenThrow(new CatalogBusinessRuleException("Product code " + EAN
                        + " of type EAN is carried by 2 products; resolve the duplicates before matching"));

        mockMvc.perform(get("/v1/products/by-code").param("codeType", "EAN").param("code", EAN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("BUSINESS_RULE_VIOLATION"));
    }

    @Test
    void returns400ForAnUnsupportedCodeScheme() throws Exception {
        mockMvc.perform(get("/v1/products/by-code").param("codeType", "GTIN").param("code", EAN))
                .andExpect(status().isBadRequest());
    }
}
