package com.positivity.catalog.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.config.ProductFactReplayService;
import com.positivity.catalog.internal.dto.ServiceDto;
import com.positivity.catalog.internal.enums.OperationCategory;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.CatalogService;
import com.positivity.catalog.internal.service.LocationPriceOverrideService;
import com.positivity.catalog.internal.service.ProductCodeLookupService;
import com.positivity.catalog.internal.service.ProductDetailService;
import com.positivity.catalog.internal.service.ProductLifecycleService;
import com.positivity.catalog.internal.service.ProductMasterDataService;
import com.positivity.catalog.internal.service.ProductSearchService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * {@code GET /v1/products/services} (#2246): the capability-picker list. Which rows count as
 * claimable and their order are the repository's contract ({@code ClaimableServiceSearchTest});
 * this covers the HTTP edge — paging parameters and their bounds, the two filters reaching the
 * service, the 400 for an unknown category, and the permission.
 */
@WebMvcTest(ProductController.class)
@Import({TestSecurityConfig.class, CatalogExceptionHandler.class})
@ActiveProfiles("test")
@DisplayName("GET /v1/products/services (#2246)")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class ClaimableServicesControllerTest {

    private static final String BASE = "/v1/products/services";
    private static final String AUTHORITIES = "X-Authorities";
    private static final UUID SERVICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    CatalogService catalogService;

    @MockitoBean
    ProductCodeLookupService productCodeLookupService;

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
    void setUp() {
        lenient().when(clock.instant()).thenReturn(Instant.EPOCH);
    }

    private static ServiceDto oilChange() {
        ServiceDto dto = new ServiceDto();
        dto.setId(SERVICE_ID);
        dto.setName("Oil Change");
        dto.setOperationCode("MNT-OIL");
        dto.setOperationCategory("MAINTENANCE");
        return dto;
    }

    @Test
    @DisplayName("returns a page of services with name, operation code and category")
    void returnsAPageOfServices() throws Exception {
        when(catalogService.listClaimableServices(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(oilChange()), PageRequest.of(0, 50), 1));

        mockMvc.perform(get(BASE).header(AUTHORITIES, CatalogPermissions.SERVICE_TYPE_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(SERVICE_ID.toString()))
                .andExpect(jsonPath("$.content[0].name").value("Oil Change"))
                .andExpect(jsonPath("$.content[0].operationCode").value("MNT-OIL"))
                .andExpect(jsonPath("$.content[0].operationCategory").value("MAINTENANCE"));
    }

    @Test
    @DisplayName("the page metadata sits at the top level, as the ServiceDtoPage schema documents")
    void pageMetadataMatchesTheDocumentedSchema() throws Exception {
        when(catalogService.listClaimableServices(isNull(), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(oilChange()), PageRequest.of(1, 1), 3));

        mockMvc.perform(get(BASE).header(AUTHORITIES, CatalogPermissions.SERVICE_TYPE_VIEW))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.number").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.numberOfElements").value(1))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(false))
                .andExpect(jsonPath("$.empty").value(false));
    }

    @Test
    @DisplayName("page defaults to 0 and size to 50")
    void pagingDefaults() throws Exception {
        when(catalogService.listClaimableServices(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get(BASE)).andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(catalogService).listClaimableServices(isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(50);
    }

    @Test
    @DisplayName("the requested page, size and both filters reach the service")
    void passesPagingAndFiltersThrough() throws Exception {
        when(catalogService.listClaimableServices(any(), any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(oilChange())));

        mockMvc.perform(get(BASE)
                        .param("operationCategory", "TIRE_SERVICE")
                        .param("q", "rot")
                        .param("page", "2")
                        .param("size", "200"))
                .andExpect(status().isOk());

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(catalogService).listClaimableServices(eq(OperationCategory.TIRE_SERVICE), eq("rot"), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(200);
    }

    @Test
    @DisplayName("a size above 200 is a 400")
    void rejectsAnOversizedPage() throws Exception {
        mockMvc.perform(get(BASE).param("size", "201"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("a size of 0 is a 400")
    void rejectsAZeroSize() throws Exception {
        mockMvc.perform(get(BASE).param("size", "0")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("a negative page is a 400")
    void rejectsANegativePage() throws Exception {
        mockMvc.perform(get(BASE).param("page", "-1")).andExpect(status().isBadRequest());
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("an unknown operation category is a 400 ApiError")
    void rejectsAnUnknownCategory() throws Exception {
        mockMvc.perform(get(BASE).param("operationCategory", "BODYWORK"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(catalogService);
    }

    @Test
    @DisplayName("without the service-type view permission the list is forbidden")
    void withoutPermissionIsForbidden() throws Exception {
        mockMvc.perform(get(BASE).header(AUTHORITIES, "catalog:product:view")).andExpect(status().isForbidden());
        verifyNoInteractions(catalogService);
    }
}
