package com.positivity.catalog.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.CatalogBulkIngestRecord;
import com.positivity.catalog.internal.dto.ProductDto;
import com.positivity.catalog.internal.service.ProductMasterDataService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@WebMvcTest(CatalogBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class CatalogBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000020");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID PRODUCT_ID = UUID.fromString("00000000-0000-0000-0000-000000000022");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    ProductMasterDataService productMasterDataService;

    @MockitoBean
    com.positivity.catalog.internal.service.CategoryNameResolver categoryNameResolver;

    // ─── category / subcategory resolution by name ───────────────────────────

    private static final UUID ELECTRICAL_SYSTEM_ID = UUID.fromString("01960030-0000-7000-8000-000000000004");
    private static final UUID BATTERIES_ID = UUID.fromString("01960031-0000-7000-8000-00000000000e");
    private static final UUID TIRES_AND_WHEELS_ID = UUID.fromString("01960030-0000-7000-8000-000000000001");

    private BulkIngestRequest<CatalogBulkIngestRecord> singleRecordRequest(CatalogBulkIngestRecord ingestRecord) {
        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));
        return request;
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_resolvesCategoryAndSubcategoryNamesOntoTheCreateRequest() throws Exception {
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("MOT-BAT-31");
        ingestRecord.setName("Group 31 AGM Battery");
        ingestRecord.setCategoryName("Electrical System");
        ingestRecord.setSubcategoryName("Batteries");

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);
        when(categoryNameResolver.resolveCategoryId("Electrical System")).thenReturn(ELECTRICAL_SYSTEM_ID);
        when(categoryNameResolver.resolveSubcategoryId("Batteries")).thenReturn(BATTERIES_ID);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleRecordRequest(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));

        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.catalog.internal.dto.ProductCreateRequestDto.class);
        org.mockito.Mockito.verify(productMasterDataService).createProduct(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCategoryId())
                .isEqualTo(ELECTRICAL_SYSTEM_ID);
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSubcategoryId())
                .isEqualTo(BATTERIES_ID);
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_unknownCategoryName_failsThatRowRatherThanLandingUncategorized() throws Exception {
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("BAD-CAT-1");
        ingestRecord.setName("Mystery Part");
        ingestRecord.setCategoryName("Sprockets");

        when(categoryNameResolver.resolveCategoryId("Sprockets"))
                .thenThrow(new com.positivity.catalog.internal.exception.CatalogValidationException(
                        "Category not found by name: Sprockets"));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleRecordRequest(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("CATALOG_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage").value("Category not found by name: Sprockets"));

        org.mockito.Mockito.verify(productMasterDataService, org.mockito.Mockito.never())
                .createProduct(any());
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_unknownSubcategoryName_failsThatRow() throws Exception {
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("BAD-SUB-1");
        ingestRecord.setName("Mystery Part");
        ingestRecord.setSubcategoryName("Flux Capacitors");

        when(categoryNameResolver.resolveSubcategoryId("Flux Capacitors"))
                .thenThrow(new com.positivity.catalog.internal.exception.CatalogValidationException(
                        "Subcategory not found by name: Flux Capacitors"));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleRecordRequest(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1));

        org.mockito.Mockito.verify(productMasterDataService, org.mockito.Mockito.never())
                .createProduct(any());
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_oneBadCategoryRow_doesNotFailTheWholeBatch() throws Exception {
        CatalogBulkIngestRecord good = new CatalogBulkIngestRecord();
        good.setSku("GOOD-1");
        good.setName("Group 31 AGM Battery");
        good.setCategoryName("Electrical System");

        CatalogBulkIngestRecord bad = new CatalogBulkIngestRecord();
        bad.setSku("BAD-1");
        bad.setName("Mystery Part");
        bad.setCategoryName("Sprockets");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(good, bad));

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);
        when(categoryNameResolver.resolveCategoryId("Electrical System")).thenReturn(ELECTRICAL_SYSTEM_ID);
        when(categoryNameResolver.resolveCategoryId("Sprockets"))
                .thenThrow(new com.positivity.catalog.internal.exception.CatalogValidationException(
                        "Category not found by name: Sprockets"));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].success").value(true))
                .andExpect(jsonPath("$.results[1].success").value(false));
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_absentCategoryNames_landUncategorizedWithoutError() throws Exception {
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("NOCAT-1");
        ingestRecord.setName("Unclassified Widget");

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);
        when(categoryNameResolver.resolveCategoryId(null)).thenReturn(null);
        when(categoryNameResolver.resolveSubcategoryId(null)).thenReturn(null);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleRecordRequest(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.catalog.internal.dto.ProductCreateRequestDto.class);
        org.mockito.Mockito.verify(productMasterDataService).createProduct(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCategoryId())
                .isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSubcategoryId())
                .isNull();
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_rowWithContradictoryCategoryPair_failsThatRowOnlyWithCatalogIngestFailed() throws Exception {
        // #1536: both names resolve, so CategoryNameResolver is happy; the pair invariant is enforced
        // downstream in ProductMasterDataServiceImpl and surfaces here as an ordinary per-row failure.
        CatalogBulkIngestRecord contradictory = new CatalogBulkIngestRecord();
        contradictory.setSku("MOT-BAT-31");
        contradictory.setName("Group 31 AGM Battery");
        contradictory.setCategoryName("Tires & Wheels");
        contradictory.setSubcategoryName("Batteries");

        CatalogBulkIngestRecord consistent = new CatalogBulkIngestRecord();
        consistent.setSku("MOT-BAT-65");
        consistent.setName("Group 65 AGM Battery");
        consistent.setCategoryName("Electrical System");
        consistent.setSubcategoryName("Batteries");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(contradictory, consistent));

        when(categoryNameResolver.resolveCategoryId("Tires & Wheels")).thenReturn(TIRES_AND_WHEELS_ID);
        when(categoryNameResolver.resolveCategoryId("Electrical System")).thenReturn(ELECTRICAL_SYSTEM_ID);
        when(categoryNameResolver.resolveSubcategoryId("Batteries")).thenReturn(BATTERIES_ID);

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenAnswer(invocation -> {
            var created = (com.positivity.catalog.internal.dto.ProductCreateRequestDto) invocation.getArgument(0);
            if (TIRES_AND_WHEELS_ID.equals(created.getCategoryId())) {
                throw new com.positivity.catalog.internal.exception.CatalogBusinessRuleException(
                        "Subcategory 'Batteries' belongs to category 'Electrical System' (" + ELECTRICAL_SYSTEM_ID
                                + "), not 'Tires & Wheels' (" + TIRES_AND_WHEELS_ID + ")");
            }
            return productDto;
        });

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].success").value(false))
                .andExpect(jsonPath("$.results[0].errorCode").value("CATALOG_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage")
                        .value(org.hamcrest.Matchers.allOf(
                                org.hamcrest.Matchers.containsString("Batteries"),
                                org.hamcrest.Matchers.containsString("Electrical System"))))
                .andExpect(jsonPath("$.results[1].success").value(true));
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_rowWithSubcategoryNameOnly_derivesCategory() throws Exception {
        // #1536: a blank categoryName column is legal — the parent is derived from the subcategory by
        // ProductMasterDataServiceImpl, so the controller forwards a null categoryId untouched.
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("MOT-BAT-31");
        ingestRecord.setName("Group 31 AGM Battery");
        ingestRecord.setSubcategoryName("Batteries");

        com.positivity.catalog.internal.dto.CategoryDto derived = new com.positivity.catalog.internal.dto.CategoryDto();
        derived.setId(ELECTRICAL_SYSTEM_ID);
        derived.setName("Electrical System");
        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        productDto.setCategory(derived);

        when(categoryNameResolver.resolveCategoryId(null)).thenReturn(null);
        when(categoryNameResolver.resolveSubcategoryId("Batteries")).thenReturn(BATTERIES_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(singleRecordRequest(ingestRecord))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));

        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.catalog.internal.dto.ProductCreateRequestDto.class);
        org.mockito.Mockito.verify(productMasterDataService).createProduct(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCategoryId())
                .isNull();
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getSubcategoryId())
                .isEqualTo(BATTERIES_ID);
    }

    // ─── POST /v1/catalog/bulk-ingest — 200 OK ───────────────────────────────

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_validRequest_returns200WithResults() throws Exception {
        CatalogBulkIngestRecord catalogRecord = new CatalogBulkIngestRecord();
        catalogRecord.setSku("ABC-001");
        catalogRecord.setName("Widget");
        catalogRecord.setPrice(new BigDecimal("9.99"));

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(catalogRecord));

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);

        when(productMasterDataService.createProduct(any())).thenReturn(productDto);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_forwardsMpnAndUnitOfMeasure_withFallbacks() throws Exception {
        CatalogBulkIngestRecord withValues = new CatalogBulkIngestRecord();
        withValues.setSku("ABC-001");
        withValues.setName("Widget");
        withValues.setMpn("MPN-42");
        withValues.setUnitOfMeasure("KIT");

        CatalogBulkIngestRecord withoutValues = new CatalogBulkIngestRecord();
        withoutValues.setSku("ABC-002");
        withoutValues.setName("Gadget");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(withValues, withoutValues));

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2));

        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.catalog.internal.dto.ProductCreateRequestDto.class);
        org.mockito.Mockito.verify(productMasterDataService, org.mockito.Mockito.times(2))
                .createProduct(captor.capture());
        var requests = captor.getAllValues();
        org.assertj.core.api.Assertions.assertThat(requests.get(0).getMpn()).isEqualTo("MPN-42");
        org.assertj.core.api.Assertions.assertThat(requests.get(0).getUnitOfMeasure())
                .isEqualTo("KIT");
        org.assertj.core.api.Assertions.assertThat(requests.get(1).getMpn()).isEqualTo("ABC-002");
        org.assertj.core.api.Assertions.assertThat(requests.get(1).getUnitOfMeasure())
                .isEqualTo("EA");
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_forwardsManufacturerCountryAndType() throws Exception {
        CatalogBulkIngestRecord ingestRecord = new CatalogBulkIngestRecord();
        ingestRecord.setSku("BSCH-SP9657");
        ingestRecord.setName("Bosch Double Iridium Spark Plug 9657");
        ingestRecord.setManufacturerName("Bosch");
        ingestRecord.setManufacturerBrand("Bosch Blue");
        ingestRecord.setCountryOfOrigin("DE");
        ingestRecord.setType("PART");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(ingestRecord));

        ProductDto productDto = new ProductDto();
        productDto.setId(PRODUCT_ID);
        when(productMasterDataService.createProduct(any())).thenReturn(productDto);

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        var captor =
                org.mockito.ArgumentCaptor.forClass(com.positivity.catalog.internal.dto.ProductCreateRequestDto.class);
        org.mockito.Mockito.verify(productMasterDataService).createProduct(captor.capture());
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getManufacturerName())
                .isEqualTo("Bosch");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getManufacturerBrand())
                .isEqualTo("Bosch Blue");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getCountryOfOrigin())
                .isEqualTo("DE");
        org.assertj.core.api.Assertions.assertThat(captor.getValue().getType()).isEqualTo("PART");
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_whenServiceThrows_recordsAsFailure() throws Exception {
        CatalogBulkIngestRecord catalogRecord = new CatalogBulkIngestRecord();
        catalogRecord.setSku("BAD-001");
        catalogRecord.setName("Broken Widget");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(catalogRecord));

        when(productMasterDataService.createProduct(any())).thenThrow(new IllegalArgumentException("Duplicate SKU"));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(1))
                .andExpect(jsonPath("$.successCount").value(0))
                .andExpect(jsonPath("$.failureCount").value(1));
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_emptyRecords_returns400() throws Exception {
        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of()); // @NotEmpty constraint

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(authorities = {"catalog:product:create"})
    void bulkIngest_missingJobId_returns400() throws Exception {
        CatalogBulkIngestRecord catalogRecord = new CatalogBulkIngestRecord();
        catalogRecord.setSku("ABC-001");
        catalogRecord.setName("Widget");

        BulkIngestRequest<CatalogBulkIngestRecord> request = new BulkIngestRequest<>();
        // jobId is null — @NotNull constraint
        request.setLocationId(LOCATION_ID);
        request.setRecords(List.of(catalogRecord));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void bulkIngest_forbiddenWhenMissingAuthority() throws Exception {
        CatalogBulkIngestRecord catalogRecord = new CatalogBulkIngestRecord();
        catalogRecord.setSku("FORBIDDEN-001");
        catalogRecord.setName("Unauthorized Widget");

        BulkIngestRequest<CatalogBulkIngestRecord> req = new BulkIngestRequest<>();
        req.setJobId(java.util.UUID.randomUUID());
        req.setLocationId(java.util.UUID.randomUUID());
        req.setOperatorId("op-1");
        req.setRecords(List.of(catalogRecord));

        mockMvc.perform(post("/v1/catalog/bulk-ingest")
                        .header("X-Authorities", "wrong:authority")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }
}
