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
import com.positivity.catalog.service.ProductMasterDataService;
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
