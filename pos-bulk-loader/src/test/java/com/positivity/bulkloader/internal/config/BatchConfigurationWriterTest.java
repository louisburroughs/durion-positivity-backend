package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.VehicleBulkRecord;
import com.positivity.bulkloader.internal.domain.VehicleFitmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleFitmentRecord;
import com.positivity.bulkloader.internal.domain.VehicleLoaderStrategy;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@SuppressWarnings({"java:S100", "java:S1192"})
@ExtendWith(MockitoExtension.class)
class BatchConfigurationWriterTest {

    private static final String VALID_JOB_ID = "00000000-0000-0000-0000-000000000001";
    private static final String VALID_LOCATION_ID = "00000000-0000-0000-0000-000000000002";
    private static final String VALID_OPERATOR_ID = "op-001";

    @Mock
    JobRepository jobRepository;

    @Mock
    PlatformTransactionManager transactionManager;

    @Mock
    CatalogLoaderStrategy catalogLoaderStrategy;

    @Mock
    CustomerLoaderStrategy customerLoaderStrategy;

    @Mock
    PersonLoaderStrategy personLoaderStrategy;

    @Mock
    BasePriceLoaderStrategy basePriceLoaderStrategy;

    @Mock
    VehicleLoaderStrategy vehicleLoaderStrategy;

    @Mock
    VehicleFitmentLoaderStrategy vehicleFitmentLoaderStrategy;

    @Mock
    RestClient.Builder restClientBuilder;

    @Mock
    RestClient mockRestClient;

    @Mock
    RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock(answer = Answers.RETURNS_SELF)
    RestClient.RequestBodySpec requestBodySpec;

    @Mock
    RestClient.ResponseSpec responseSpec;

    @InjectMocks
    BatchConfiguration batchConfiguration;

    BulkLoadAuthorizationContext bulkLoadAuthorizationContext = new BulkLoadAuthorizationContext();

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        lenient().when(restClientBuilder.baseUrl(nullable(String.class))).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.build()).thenReturn(mockRestClient);
        lenient().when(mockRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        batchConfiguration = new BatchConfiguration(
                jobRepository,
                transactionManager,
                catalogLoaderStrategy,
                customerLoaderStrategy,
                personLoaderStrategy,
                basePriceLoaderStrategy,
                vehicleLoaderStrategy,
                vehicleFitmentLoaderStrategy,
                bulkLoadAuthorizationContext,
                null,
                null,
                null);
    }

    // --- catalogBulkIngestWriter ---

    @Test
    void catalogBulkIngestWriter_happyPath_postsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/catalog/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "catalog:product:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void catalogBulkIngestWriter_withBearerToken_relaysAuthorizationAndGatewayToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(requestBodySpec).header(HttpHeaders.AUTHORIZATION, "Bearer token-123");
        verify(requestBodySpec).header(GatewaySecurityConstants.HEADER_TOKEN, "token-123");
    }

    @Test
    void catalogBulkIngestWriter_withLaunchAuthorizationContext_relaysAuthorizationWithoutRequestContext() {
        bulkLoadAuthorizationContext.setAuthorizationHeader("Bearer token-launch");

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(requestBodySpec).header(HttpHeaders.AUTHORIZATION, "Bearer token-launch");
        verify(requestBodySpec).header(GatewaySecurityConstants.HEADER_TOKEN, "token-launch");
    }

    @Test
    void catalogBulkIngestWriter_nullJobId_skipsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void catalogBulkIngestWriter_nullLocationId_skipsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<CatalogProductRecord> writer =
                batchConfiguration.catalogBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- customerBulkIngestWriter ---

    @Test
    void customerBulkIngestWriter_happyPath_postsChunk() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");
        person.setLastName("Doe");

        ItemWriter<CustomerPersonRecord> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(person))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/customer/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "crm:party:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void customerBulkIngestWriter_nullJobId_skipsChunk() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<CustomerPersonRecord> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(person))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void customerBulkIngestWriter_nullLocationId_skipsChunk() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<CustomerPersonRecord> writer =
                batchConfiguration.customerBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(person))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- peopleBulkIngestWriter ---

    @Test
    void peopleBulkIngestWriter_happyPath_postsChunk() {
        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");
        employee.setEmployeeNumber("EMP-001");
        employee.setHireDate("2020-01-01");

        ItemWriter<PersonRecord> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(employee))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/people/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "people:employee:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void peopleBulkIngestWriter_nullJobId_skipsChunk() {
        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");

        ItemWriter<PersonRecord> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(employee))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void peopleBulkIngestWriter_nullLocationId_skipsChunk() {
        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");

        ItemWriter<PersonRecord> writer =
                batchConfiguration.peopleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(employee))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- priceBulkIngestWriter ---

    @Test
    void priceBulkIngestWriter_happyPath_postsChunk() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");
        priceEntry.setMsrp("9.99");
        priceEntry.setCurrency("USD");
        priceEntry.setEffectiveFrom("2024-01-01");

        ItemWriter<BasePriceRecord> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(priceEntry))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/price/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "pricing:base_price:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void priceBulkIngestWriter_nullJobId_skipsChunk() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<BasePriceRecord> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(priceEntry))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void priceBulkIngestWriter_nullLocationId_skipsChunk() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<BasePriceRecord> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(priceEntry))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- vehicleBulkIngestWriter ---

    @Test
    void vehicleBulkIngestWriter_happyPath_postsChunk() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicle.setVin("1HGCM82633A004352");
        vehicle.setUnitNumber("V-001");
        vehicle.setDescription("Sedan");
        vehicle.setYear("2022");

        ItemWriter<VehicleBulkRecord> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/vehicles/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "vehicle-inventory:registry:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void vehicleBulkIngestWriter_nullJobId_skipsChunk() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<VehicleBulkRecord> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void vehicleBulkIngestWriter_nullLocationId_skipsChunk() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<VehicleBulkRecord> writer =
                batchConfiguration.vehicleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- vehicleFitmentBulkIngestWriter ---

    @Test
    void vehicleFitmentBulkIngestWriter_happyPath_postsChunk() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");
        fitment.setManufacturerName("Bosch");
        fitment.setMakeName("Honda");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/fitments/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "vehicle-fitment:hint:create");
        verify(requestBodySpec).header("X-User", VALID_OPERATOR_ID);
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        assertThat(bodyCaptor.getValue()).isInstanceOf(com.positivity.bulkingest.BulkIngestRequest.class);
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getJobId()).isEqualTo(java.util.UUID.fromString(VALID_JOB_ID));
        assertThat(request.getLocationId()).isEqualTo(java.util.UUID.fromString(VALID_LOCATION_ID));
        assertThat(request.getOperatorId()).isEqualTo(VALID_OPERATOR_ID);
        assertThat(request.getRecords()).hasSize(1);
    }

    @Test
    void vehicleFitmentBulkIngestWriter_nullJobId_skipsChunk() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void vehicleFitmentBulkIngestWriter_nullLocationId_skipsChunk() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- RestClientException rethrow tests ---

    @Test
    void catalogBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<CatalogProductRecord> chunk = Chunk.of(product);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void customerBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<CustomerPersonRecord> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<CustomerPersonRecord> chunk = Chunk.of(person);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void peopleBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");

        ItemWriter<PersonRecord> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<PersonRecord> chunk = Chunk.of(employee);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void priceBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<BasePriceRecord> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<BasePriceRecord> chunk = Chunk.of(priceEntry);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void vehicleBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<VehicleBulkRecord> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<VehicleBulkRecord> chunk = Chunk.of(vehicle);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void vehicleFitmentBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("test error"));

        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setManufacturerName("Bosch");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<VehicleFitmentRecord> chunk = Chunk.of(fitment);

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    // --- Malformed UUID skip tests ---

    @Test
    void catalogBulkIngestWriter_skipsChunk_onMalformedJobId() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<CatalogProductRecord> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void customerBulkIngestWriter_skipsChunk_onMalformedJobId() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<CustomerPersonRecord> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(person))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void peopleBulkIngestWriter_skipsChunk_onMalformedJobId() {
        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");

        ItemWriter<PersonRecord> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(employee))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void priceBulkIngestWriter_skipsChunk_onMalformedJobId() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<BasePriceRecord> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(priceEntry))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void vehicleBulkIngestWriter_skipsChunk_onMalformedJobId() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<VehicleBulkRecord> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void vehicleFitmentBulkIngestWriter_skipsChunk_onMalformedJobId() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setManufacturerName("Bosch");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    // --- Type conversion error tests ---

    @Test
    void vehicleBulkIngestWriter_handlesInvalidAccountId_andYear() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setAccountId("not-a-uuid");
        vehicle.setYear("not-a-number");
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<VehicleBulkRecord> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verify(mockRestClient).post();
    }

    @Test
    void vehicleFitmentBulkIngestWriter_handlesInvalidPartNumberId() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("not-a-long");
        fitment.setManufacturerName("Bosch");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verify(mockRestClient).post();
    }

    // --- Blank operatorId sanitization (PRCR-001) ---

    @Test
    void catalogBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<CatalogProductRecord> writer =
                batchConfiguration.catalogBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(product))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "catalog:product:create");
    }

    @Test
    void customerBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");
        person.setLastName("Doe");

        ItemWriter<CustomerPersonRecord> writer =
                batchConfiguration.customerBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(person))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "crm:party:create");
    }

    @Test
    void peopleBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        PersonRecord employee = new PersonRecord();
        employee.setLegalName("Jane Smith");
        employee.setEmployeeNumber("EMP-001");
        employee.setHireDate("2020-01-01");

        ItemWriter<PersonRecord> writer =
                batchConfiguration.peopleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(employee))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "people:employee:create");
    }

    @Test
    void priceBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");
        priceEntry.setMsrp("9.99");
        priceEntry.setCurrency("USD");
        priceEntry.setEffectiveFrom("2024-01-01");

        ItemWriter<BasePriceRecord> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(priceEntry))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "pricing:base_price:create");
    }

    @Test
    void vehicleBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setAccountId("00000000-0000-0000-0000-000000000001");
        vehicle.setVin("1HGCM82633A004352");
        vehicle.setUnitNumber("V-001");
        vehicle.setDescription("Sedan");
        vehicle.setYear("2022");

        ItemWriter<VehicleBulkRecord> writer =
                batchConfiguration.vehicleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(vehicle))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "vehicle-inventory:registry:create");
    }

    @Test
    void vehicleFitmentBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");
        fitment.setManufacturerName("Bosch");
        fitment.setMakeName("Honda");

        ItemWriter<VehicleFitmentRecord> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(fitment))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "vehicle-fitment:hint:create");
    }
}
