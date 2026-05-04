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
import com.positivity.bulkloader.PosBlkLoaderApplication;
import com.positivity.bulkloader.internal.config.BulkLoaderEventTypeInitializer;
import com.positivity.bulkloader.internal.config.PermissionRegistration;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;

@SpringBootTest(classes = PosBlkLoaderApplication.class, properties = {
    "bulk-loader.storage.local-root=${java.io.tmpdir}/bulk-loader-e2e",
    "pos.security.permission-registration.enabled=false"
})
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@SuppressWarnings({ "java:S100", "java:S1192" })
class FileUploadProcessEndToEndTest {

  private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000041");
  private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000042");
  private static final Path STORAGE_ROOT = Path.of(System.getProperty("java.io.tmpdir"), "bulk-loader-e2e");

  @Autowired
  MockMvc mockMvc;

  @Autowired
  BulkLoadJobRepository bulkLoadJobRepository;

  @Autowired
  BulkLoadAuthorizationContext bulkLoadAuthorizationContext;

  @MockitoBean
  RestClient.Builder restClientBuilder;

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
    bulkLoadJobRepository.deleteAll();

    mockRestClient = mock(RestClient.class);
    requestBodyUriSpec = mock(RestClient.RequestBodyUriSpec.class);
    requestBodySpec = mock(RestClient.RequestBodySpec.class, Answers.RETURNS_SELF);
    responseSpec = mock(RestClient.ResponseSpec.class);

    when(restClientBuilder.baseUrl(nullable(String.class))).thenReturn(restClientBuilder);
    when(restClientBuilder.build()).thenReturn(mockRestClient);
    when(mockRestClient.post()).thenReturn(requestBodyUriSpec);
    when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
    when(requestBodySpec.retrieve()).thenReturn(responseSpec);
    when(responseSpec.toBodilessEntity()).thenReturn(ResponseEntity.ok().build());
  }

  @AfterEach
  void tearDown() throws IOException {
    bulkLoadJobRepository.deleteAll();
    if (Files.exists(STORAGE_ROOT)) {
      try (var paths = Files.walk(STORAGE_ROOT)) {
        paths.sorted((left, right) -> right.compareTo(left))
            .forEach(path -> {
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
    Files.writeString(
        STORAGE_ROOT.resolve(relativeStoragePath),
        """
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
}