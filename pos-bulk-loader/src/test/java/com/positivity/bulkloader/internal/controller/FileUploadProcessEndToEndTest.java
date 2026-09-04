package com.positivity.bulkloader.internal.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.bulkloader.PosBlkLoaderApplication;
import com.positivity.bulkloader.internal.config.BulkLoaderEventTypeInitializer;
import com.positivity.bulkloader.internal.config.PermissionRegistration;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.entity.BulkLoadRecordAudit;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.enums.ReviewStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadRecordAuditRepository;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;

@SpringBootTest(
        classes = PosBlkLoaderApplication.class,
        properties = {
            "bulk-loader.storage.local-root=${java.io.tmpdir}/bulk-loader-e2e",
            "pos.security.permission-registration.enabled=false"
        })
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@SuppressWarnings({"java:S100", "java:S1192"})
class FileUploadProcessEndToEndTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final Path STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "bulk-loader-e2e");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    BulkLoadJobRepository bulkLoadJobRepository;

    @Autowired
    BulkLoadRecordAuditRepository bulkLoadRecordAuditRepository;

    @Autowired
    BulkLoadAuthorizationContext bulkLoadAuthorizationContext;

    @MockitoBean
    RestClient.Builder restClientBuilder;

    // The ingest writers inject the load-balanced builder (#641), so it must be mocked
    // alongside the plain builder to keep the e2e flow off the network.
    @MockitoBean(name = "loadBalancedRestClientBuilder")
    RestClient.Builder loadBalancedRestClientBuilder;

    @MockitoBean
    BulkLoaderEventTypeInitializer bulkLoaderEventTypeInitializer;

    @MockitoBean
    PermissionRegistration permissionRegistration;

    private RestClient mockRestClient;
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;
    private RestClient.RequestBodySpec requestBodySpec;
    private RestClient.ResponseSpec responseSpec;

    @BeforeEach
    void setUp() throws IOException {
        Files.createDirectories(STORAGE_ROOT);
        bulkLoadRecordAuditRepository.deleteAll();
        bulkLoadJobRepository.deleteAll();

        mockRestClient = mock(RestClient.class);
        requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
        requestBodySpec = mock(RestClient.RequestBodySpec.class, Answers.RETURNS_SELF);
        responseSpec = mock(RestClient.ResponseSpec.class);

        when(restClientBuilder.baseUrl(nullable(String.class))).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(mockRestClient);
        when(loadBalancedRestClientBuilder.baseUrl(nullable(String.class))).thenReturn(loadBalancedRestClientBuilder);
        when(loadBalancedRestClientBuilder.build()).thenReturn(mockRestClient);
        when(mockRestClient.post()).thenReturn(requestBodyUriSpec);
        when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
    }

    @AfterEach
    void tearDown() throws IOException {
        bulkLoadRecordAuditRepository.deleteAll();
        bulkLoadJobRepository.deleteAll();
        if (Files.exists(STORAGE_ROOT)) {
            try (var paths = Files.walk(STORAGE_ROOT)) {
                paths.sorted((left, right) -> right.compareTo(left)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ex) {
                        throw new RuntimeException(ex);
                    }
                });
            } catch (RuntimeException ex) {
                if (ex.getCause() instanceof IOException ioException) {
                    throw ioException;
                }
                throw ex;
            }
        }
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void startProcessing_endToEnd_relaysGatewayTokenIntoCatalogWriter() throws Exception {
        String relativeStoragePath = JOB_ID + "/catalog-products.csv";
        Files.createDirectories(STORAGE_ROOT.resolve(JOB_ID.toString()));
        Files.writeString(STORAGE_ROOT.resolve(relativeStoragePath), """
            sku,upc,name,description,categoryName,subcategoryName,price
            SKU-001,,Widget,Widget Description,Parts,Filters,19.99
            """);

        BulkLoadJob job = new BulkLoadJob();
        job.setId(JOB_ID);
        job.setOperatorId("test-operator");
        job.setLocationId(LOCATION_ID);
        job.setFileName("catalog-products.csv");
        job.setOriginalFilePath(relativeStoragePath);
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(JobStatus.UPLOADING);
        bulkLoadJobRepository.saveAndFlush(job);

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID)
                        .header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()));

        verify(requestBodyUriSpec, timeout(2000)).uri("/v1/catalog/bulk-ingest");
        verify(requestBodySpec, timeout(2000)).header(HttpHeaders.AUTHORIZATION, "Bearer token-e2e");
        verify(requestBodySpec, timeout(2000)).header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e");
        verify(requestBodySpec, timeout(2000)).header("X-Authorities", "catalog:product:create");
        verify(requestBodySpec, timeout(2000)).header("X-User", "test-operator");

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec, timeout(2000)).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(BulkIngestRequest.class);

        @SuppressWarnings("unchecked")
        BulkIngestRequest<Object> request = (BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(JOB_ID);
        assertThat(request.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(request.getOperatorId()).isEqualTo("test-operator");
        assertThat(request.getRecords()).hasSize(1);
        assertThat(bulkLoadAuthorizationContext.getAuthorizationHeader()).isNull();
    }

    /**
     * Proves the issue #1694 catch-site audit's central claim for this module: a bad record
     * inside an uploaded file is never an HTTP error, and never aborts the rest of the import.
     * {@link com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy#validate} rejects
     * the blank-sku row through the loader's own
     * per-row error report ({@code recordRejected} → {@link BulkLoadRecordAudit}, {@code
     * ReviewStatus.PENDING}) — not by throwing — so the second, valid row still reaches the
     * writer and is posted to the owning service exactly as if the bad row were never in the
     * file.
     */
    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void startProcessing_endToEnd_badRecordRejectedButGoodRecordStillPosted() throws Exception {
        String relativeStoragePath = JOB_ID + "/catalog-products-mixed.csv";
        Files.createDirectories(STORAGE_ROOT.resolve(JOB_ID.toString()));
        Files.writeString(STORAGE_ROOT.resolve(relativeStoragePath), """
            sku,upc,name,description,categoryName,subcategoryName,price
            ,,Widget With No Sku,Widget Description,Parts,Filters,19.99
            SKU-002,,Widget,Widget Description,Parts,Filters,24.99
            """);

        saveJob("catalog-products-mixed.csv", relativeStoragePath);

        // The one record that reaches the writer (row 1's SKU-002) is answered as accepted, so
        // its audit row lands as APPROVED rather than falling into the "no usable response"
        // failure path the recorder also treats as PENDING — isolating the assertion below to
        // exactly the loader-side rejection this test is about.
        when(responseSpec.body(BulkIngestResponse.class))
                .thenReturn(BulkIngestResponse.builder()
                        .totalSubmitted(1)
                        .successCount(1)
                        .failureCount(0)
                        .results(List.of(BulkIngestResult.builder()
                                .rowIndex(0)
                                .success(true)
                                .entityId(UUID.randomUUID())
                                .build()))
                        .build());

        // No custom TaskExecutor/JobLauncher bean is configured anywhere in this module, so
        // Spring Boot Batch's default JobOperator runs the job with a SyncTaskExecutor: this
        // POST does not return until the whole batch run (reader, processor, writer and
        // BulkLoadJobExecutionListener#afterJob) has completed on this same thread — despite the
        // controller's own Javadoc calling it "asynchronous". So the response already carries the
        // finished run's row counts, and every assertion below can run immediately after, with no
        // polling needed.
        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID)
                        .header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(JOB_ID.toString()))
                .andExpect(jsonPath("$.processedRows").value(2))
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1));

        // The good row (row 1, 0-indexed) is the only one posted — the bad row never reaches the
        // writer at all, so the chunk the owning service sees is exactly as if row 0 were absent.
        BulkIngestRequest<CatalogProductRecord> request = capturedCatalogRequest();
        assertThat(request.getRecords()).hasSize(1);
        assertThat(request.getRecords().get(0).getSku()).isEqualTo("SKU-002");

        // The bad row is recorded in the reviewable error report instead, keyed to its own file
        // line, rather than failing the whole job — and the good row's own audit row confirms it
        // was actually accepted, not silently dropped alongside it.
        List<BulkLoadRecordAudit> rejected =
                bulkLoadRecordAuditRepository.findByJobIdAndReviewStatus(JOB_ID, ReviewStatus.PENDING);
        assertThat(rejected).hasSize(1);
        BulkLoadRecordAudit rejectedAudit = rejected.get(0);
        assertThat(rejectedAudit.getRowNumber()).isZero();
        assertThat(rejectedAudit.getReasonCodes())
                .contains("BULK_LOAD_VALIDATION_FAILED")
                .contains("sku is required");

        List<BulkLoadRecordAudit> approved =
                bulkLoadRecordAuditRepository.findByJobIdAndReviewStatus(JOB_ID, ReviewStatus.APPROVED);
        assertThat(approved).hasSize(1);
        assertThat(approved.get(0).getRowNumber()).isEqualTo(1L);
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void startProcessing_vendorFormatCsv_discoversColumnsByHeader() throws Exception {
        // Vendor price-file layout: different header names, different column order, extra columns.
        String relativeStoragePath = JOB_ID + "/vendor-parts-catalog.csv";
        Files.createDirectories(STORAGE_ROOT.resolve(JOB_ID.toString()));
        Files.writeString(STORAGE_ROOT.resolve(relativeStoragePath), """
                vendor_id,vendor_name,supplier_sku,manufacturer_part_no,upc,category,subcategory,brand,product_name,description,uom,list_price,dealer_cost,currency
                VEND-MIC-001,Michelin North America,MIC-000001,3A3ZMF8MD,822676000001,Wheels,Steel Wheel,Maxion,Maxion Steel Wheel 15x6,Steel Wheel test item,EA,242.81,174.89,USD
                VEND-NAT-005,National Tire Supply,NAT-000004,IIUC039TE,896474000004,TPMS,Valve Stem,Dill,Dill Valve Stem Programmable,Programmable valve stem,KIT,96.26,62.07,USD
                """);

        saveJob("vendor-parts-catalog.csv", relativeStoragePath);

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID)
                        .header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e"))
                .andExpect(status().isOk());

        BulkIngestRequest<CatalogProductRecord> request = capturedCatalogRequest();
        assertThat(request.getRecords()).hasSize(2);
        CatalogProductRecord first = request.getRecords().get(0);
        assertThat(first.getSku()).isEqualTo("MIC-000001");
        assertThat(first.getMpn()).isEqualTo("3A3ZMF8MD");
        assertThat(first.getUpc()).isEqualTo("822676000001");
        assertThat(first.getName()).isEqualTo("Maxion Steel Wheel 15x6");
        assertThat(first.getDescription()).isEqualTo("Steel Wheel test item");
        assertThat(first.getCategoryName()).isEqualTo("Wheels");
        assertThat(first.getSubcategoryName()).isEqualTo("Steel Wheel");
        assertThat(first.getUnitOfMeasure()).isEqualTo("EA");
        assertThat(first.getPrice()).isEqualByComparingTo(new BigDecimal("242.81"));
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void startProcessing_xlsxWorkbook_selectsDataSheetAndDiscoversColumns() throws Exception {
        String relativeStoragePath = JOB_ID + "/vendor-parts-catalog.xlsx";
        Files.createDirectories(STORAGE_ROOT.resolve(JOB_ID.toString()));
        try (XSSFWorkbook workbook = new XSSFWorkbook();
                var out = Files.newOutputStream(STORAGE_ROOT.resolve(relativeStoragePath))) {
            Sheet importMap = workbook.createSheet("Import_Map");
            writeRow(importMap, 0, "Target POS Field", "Source Column");
            writeRow(importMap, 1, "SKU", "supplier_sku");

            Sheet catalog = workbook.createSheet("Catalog");
            writeRow(catalog, 0, "supplier_sku", "product_name", "category", "uom", "list_price");
            writeRow(catalog, 1, "MIC-000001", "Maxion Steel Wheel 15x6", "Wheels", "EA", "242.81");
            writeRow(catalog, 2, "NAT-000004", "Dill Valve Stem", "TPMS", "KIT", "96.26");
            workbook.write(out);
        }

        saveJob("vendor-parts-catalog.xlsx", relativeStoragePath);

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID)
                        .header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e"))
                .andExpect(status().isOk());

        BulkIngestRequest<CatalogProductRecord> request = capturedCatalogRequest();
        assertThat(request.getRecords()).hasSize(2);
        assertThat(request.getRecords().get(0).getSku()).isEqualTo("MIC-000001");
        assertThat(request.getRecords().get(1).getName()).isEqualTo("Dill Valve Stem");
    }

    @Test
    @WithMockUser(username = "test-operator", authorities = "bulkImport:upload:execute")
    void startProcessing_jsonFile_discoversRecordsByStructure() throws Exception {
        String relativeStoragePath = JOB_ID + "/vendor-parts-catalog.json";
        Files.createDirectories(STORAGE_ROOT.resolve(JOB_ID.toString()));
        Files.writeString(STORAGE_ROOT.resolve(relativeStoragePath), """
                {
                  "vendor": "Michelin North America",
                  "items": [
                    {"supplier_sku": "MIC-000001", "product_name": "Maxion Steel Wheel 15x6", "uom": "EA", "list_price": 242.81},
                    {"supplier_sku": "NAT-000004", "product_name": "Dill Valve Stem", "uom": "KIT", "list_price": 96.26}
                  ]
                }
                """);

        saveJob("vendor-parts-catalog.json", relativeStoragePath);

        mockMvc.perform(post("/v1/bulk-jobs/{jobId}/process", JOB_ID)
                        .header(GatewaySecurityConstants.HEADER_TOKEN, "token-e2e"))
                .andExpect(status().isOk());

        BulkIngestRequest<CatalogProductRecord> request = capturedCatalogRequest();
        assertThat(request.getRecords()).hasSize(2);
        CatalogProductRecord first = request.getRecords().get(0);
        assertThat(first.getSku()).isEqualTo("MIC-000001");
        assertThat(first.getUnitOfMeasure()).isEqualTo("EA");
        assertThat(first.getPrice()).isEqualByComparingTo(new BigDecimal("242.81"));
    }

    private void saveJob(String fileName, String relativeStoragePath) {
        BulkLoadJob job = new BulkLoadJob();
        job.setId(JOB_ID);
        job.setOperatorId("test-operator");
        job.setLocationId(LOCATION_ID);
        job.setFileName(fileName);
        job.setOriginalFilePath(relativeStoragePath);
        job.setDomainType(DomainType.CATALOG_PRODUCT);
        job.setStatus(JobStatus.UPLOADING);
        bulkLoadJobRepository.saveAndFlush(job);
    }

    @SuppressWarnings("unchecked")
    private BulkIngestRequest<CatalogProductRecord> capturedCatalogRequest() {
        verify(requestBodyUriSpec, timeout(2000)).uri("/v1/catalog/bulk-ingest");
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec, timeout(2000)).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(BulkIngestRequest.class);
        return (BulkIngestRequest<CatalogProductRecord>) bodyCaptor.getValue();
    }

    private void writeRow(Sheet sheet, int rowIndex, String... values) {
        Row row = sheet.createRow(rowIndex);
        for (int i = 0; i < values.length; i++) {
            row.createCell(i).setCellValue(values[i]);
        }
    }
}
