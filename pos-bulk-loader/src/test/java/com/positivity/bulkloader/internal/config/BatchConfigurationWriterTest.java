package com.positivity.bulkloader.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.bulkloader.internal.domain.BasePriceLoaderStrategy;
import com.positivity.bulkloader.internal.domain.BasePriceRecord;
import com.positivity.bulkloader.internal.domain.BayLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CatalogProductRecord;
import com.positivity.bulkloader.internal.domain.CommercialCustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CommercialCustomerRecord;
import com.positivity.bulkloader.internal.domain.CustomerLoaderStrategy;
import com.positivity.bulkloader.internal.domain.CustomerPersonRecord;
import com.positivity.bulkloader.internal.domain.CycleCountPlanLoaderStrategy;
import com.positivity.bulkloader.internal.domain.InventoryStockCountLoaderStrategy;
import com.positivity.bulkloader.internal.domain.LocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.LocationRecord;
import com.positivity.bulkloader.internal.domain.MechanicSkillLoaderStrategy;
import com.positivity.bulkloader.internal.domain.MobileUnitLoaderStrategy;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.domain.PersonLoaderStrategy;
import com.positivity.bulkloader.internal.domain.PersonRecord;
import com.positivity.bulkloader.internal.domain.PutawayRuleLoaderStrategy;
import com.positivity.bulkloader.internal.domain.SecurityUserLoaderStrategy;
import com.positivity.bulkloader.internal.domain.StaffingAssignmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.StorageLocationLoaderStrategy;
import com.positivity.bulkloader.internal.domain.UserPersonLinkLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleBulkRecord;
import com.positivity.bulkloader.internal.domain.VehicleFitmentLoaderStrategy;
import com.positivity.bulkloader.internal.domain.VehicleFitmentRecord;
import com.positivity.bulkloader.internal.domain.VehicleLoaderStrategy;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
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
    CatalogLoaderStrategy catalogLoaderStrategy;

    @Mock
    CustomerLoaderStrategy customerLoaderStrategy;

    @Mock
    CommercialCustomerLoaderStrategy commercialCustomerLoaderStrategy;

    @Mock
    LocationLoaderStrategy locationLoaderStrategy;

    @Mock
    PersonLoaderStrategy personLoaderStrategy;

    @Mock
    BasePriceLoaderStrategy basePriceLoaderStrategy;

    @Mock
    VehicleLoaderStrategy vehicleLoaderStrategy;

    @Mock
    VehicleFitmentLoaderStrategy vehicleFitmentLoaderStrategy;

    @Mock
    InventoryStockCountLoaderStrategy inventoryStockCountLoaderStrategy;

    @Mock
    StorageLocationLoaderStrategy storageLocationLoaderStrategy;

    @Mock
    BayLoaderStrategy bayLoaderStrategy;

    @Mock
    MobileUnitLoaderStrategy mobileUnitLoaderStrategy;

    @Mock
    StaffingAssignmentLoaderStrategy staffingAssignmentLoaderStrategy;

    @Mock
    PutawayRuleLoaderStrategy putawayRuleLoaderStrategy;

    @Mock
    CycleCountPlanLoaderStrategy cycleCountPlanLoaderStrategy;

    @Mock
    SecurityUserLoaderStrategy securityUserLoaderStrategy;

    @Mock
    UserPersonLinkLoaderStrategy userPersonLinkLoaderStrategy;

    @Mock
    MechanicSkillLoaderStrategy mechanicSkillLoaderStrategy;

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

    @Mock
    BulkIngestResultRecorder bulkIngestResultRecorder;

    BatchConfiguration batchConfiguration;

    /** Writers consume rows already stamped with the file line the processor read them from. */
    private static <T> NumberedRecord<T> numbered(long rowNumber, T record) {
        return new NumberedRecord<>(rowNumber, record);
    }

    BulkLoadAuthorizationContext bulkLoadAuthorizationContext = new BulkLoadAuthorizationContext();

    @BeforeEach
    void setUp() {
        RequestContextHolder.resetRequestAttributes();
        lenient().when(restClientBuilder.baseUrl(nullable(String.class))).thenReturn(restClientBuilder);
        lenient().when(restClientBuilder.build()).thenReturn(mockRestClient);
        lenient().when(mockRestClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.uri(anyString())).thenReturn(requestBodySpec);
        lenient().when(requestBodySpec.retrieve()).thenReturn(responseSpec);
        // A real writer factory, not a mock: these tests are about what actually goes over the
        // wire, and the bean methods are now thin delegations to it. The job factory is unused by
        // the writer beans under test.
        BulkIngestWriterFactory writerFactory = new BulkIngestWriterFactory(
                new AuthorizationHeaderRelay(bulkLoadAuthorizationContext), bulkIngestResultRecorder);
        batchConfiguration = new BatchConfiguration(
                null,
                writerFactory,
                catalogLoaderStrategy,
                customerLoaderStrategy,
                commercialCustomerLoaderStrategy,
                locationLoaderStrategy,
                personLoaderStrategy,
                basePriceLoaderStrategy,
                vehicleLoaderStrategy,
                vehicleFitmentLoaderStrategy,
                inventoryStockCountLoaderStrategy,
                storageLocationLoaderStrategy,
                bayLoaderStrategy,
                mobileUnitLoaderStrategy,
                staffingAssignmentLoaderStrategy,
                putawayRuleLoaderStrategy,
                cycleCountPlanLoaderStrategy,
                securityUserLoaderStrategy,
                userPersonLinkLoaderStrategy,
                mechanicSkillLoaderStrategy);
    }

    // --- catalogBulkIngestWriter ---

    @Test
    void catalogBulkIngestWriter_happyPath_postsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
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

    // --- response handling, shared by every writer via postChunkAndRecord ---

    @Test
    void bulkIngestWriter_recordsThePerRowResponse_ratherThanDiscardingIt() {
        // The Stage-1 defect: the chunk used to be posted with toBodilessEntity(), so a row the
        // owning service rejected left no trace and still counted as written.
        BulkIngestResponse response = BulkIngestResponse.builder()
                .totalSubmitted(1)
                .successCount(0)
                .failureCount(1)
                .results(List.of(BulkIngestResult.builder()
                        .rowIndex(0)
                        .success(false)
                        .errorCode("CATALOG_INGEST_FAILED")
                        .build()))
                .build();
        when(responseSpec.body(BulkIngestResponse.class)).thenReturn(response);

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();

        verify(bulkIngestResultRecorder)
                .record(
                        eq(java.util.UUID.fromString(VALID_JOB_ID)),
                        eq(DomainType.CATALOG_PRODUCT),
                        eq(List.of(0L)),
                        anyList(),
                        eq(response));
    }

    @Test
    void bulkIngestWriter_carriesThroughTheRowNumbersItIsGiven() {
        // The writer must not derive row numbers itself. It only ever sees the rows that survived
        // the processor, so counting them would name the wrong line for every row after a skip —
        // and the row number is what an operator uses to find the offending line in their file.
        when(responseSpec.body(BulkIngestResponse.class)).thenReturn(null);

        CatalogProductRecord first = new CatalogProductRecord();
        first.setSku("SKU-001");
        CatalogProductRecord second = new CatalogProductRecord();
        second.setSku("SKU-002");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        assertThatCode(() -> {
                    writer.write(Chunk.of(numbered(0, first), numbered(1, second)));
                    // Row 2 was dropped by the processor, so the writer never sees it; row 3 must
                    // still be recorded as row 3.
                    writer.write(Chunk.of(numbered(3, first)));
                })
                .doesNotThrowAnyException();

        verify(bulkIngestResultRecorder)
                .record(any(), eq(DomainType.CATALOG_PRODUCT), eq(List.of(0L, 1L)), anyList(), any());
        verify(bulkIngestResultRecorder)
                .record(any(), eq(DomainType.CATALOG_PRODUCT), eq(List.of(3L)), anyList(), any());
    }

    @Test
    void bulkIngestWriter_whenJobParametersMissing_recordsNothing() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();

        verifyNoInteractions(bulkIngestResultRecorder);
    }

    @Test
    void catalogBulkIngestWriter_withBearerToken_relaysAuthorizationAndGatewayToken() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer token-123");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verify(requestBodySpec).header(HttpHeaders.AUTHORIZATION, "Bearer token-123");
        verify(requestBodySpec).header(GatewaySecurityConstants.HEADER_TOKEN, "token-123");
    }

    @Test
    void catalogBulkIngestWriter_withLaunchAuthorizationContext_relaysAuthorizationWithoutRequestContext() {
        bulkLoadAuthorizationContext.setAuthorizationHeader("Bearer token-launch");

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verify(requestBodySpec).header(HttpHeaders.AUTHORIZATION, "Bearer token-launch");
        verify(requestBodySpec).header(GatewaySecurityConstants.HEADER_TOKEN, "token-launch");
    }

    @Test
    void catalogBulkIngestWriter_nullJobId_skipsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void catalogBulkIngestWriter_nullLocationId_skipsChunk() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer =
                batchConfiguration.catalogBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- customerBulkIngestWriter ---

    @Test
    void customerBulkIngestWriter_happyPath_postsChunk() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");
        person.setLastName("Doe");

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, person)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, person)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void customerBulkIngestWriter_nullLocationId_skipsChunk() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer =
                batchConfiguration.customerBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, person)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- commercialCustomerBulkIngestWriter ---

    @Test
    void commercialCustomerBulkIngestWriter_happyPath_postsChunk() {
        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");
        account.setDisplayName("Piedmont Freight");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, account)))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/customer/commercial/bulk-ingest");
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
    void commercialCustomerBulkIngestWriter_nullJobId_skipsChunk() {
        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, account)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void commercialCustomerBulkIngestWriter_nullLocationId_skipsChunk() {
        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, account)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void commercialCustomerBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<CommercialCustomerRecord>> chunk = Chunk.of(numbered(0, account));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void commercialCustomerBulkIngestWriter_skipsChunk_onMalformedJobId() {
        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, account)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void commercialCustomerBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        CommercialCustomerRecord account = new CommercialCustomerRecord();
        account.setLegalName("Piedmont Freight Carriers LLC");

        ItemWriter<NumberedRecord<CommercialCustomerRecord>> writer =
                batchConfiguration.commercialCustomerBulkIngestWriter(
                        restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, account)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "crm:party:create");
    }

    // --- locationBulkIngestWriter ---

    @Test
    void locationBulkIngestWriter_happyPath_postsChunk() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");
        location.setCode("CLT-SOUTH");
        location.setActive("true");

        ItemWriter<NumberedRecord<LocationRecord>> writer = batchConfiguration.locationBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        verify(requestBodyUriSpec).uri("/v1/locations/bulk-ingest");
        verify(requestBodySpec).header("X-Authorities", "location:write");
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
        assertThat(request.getRecords().get(0).toString())
                .contains("name=Charlotte South")
                .contains("code=CLT-SOUTH")
                .contains("active=true");
    }

    @Test
    void locationBulkIngestWriter_invalidActiveFlag_mapsToNull() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");
        location.setCode("CLT-SOUTH");
        location.setActive("yes-please");

        ItemWriter<NumberedRecord<LocationRecord>> writer = batchConfiguration.locationBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(requestBodySpec).body(bodyCaptor.capture());
        @SuppressWarnings("unchecked")
        var request = (com.positivity.bulkingest.BulkIngestRequest<Object>) bodyCaptor.getValue();
        assertThat(request.getRecords().get(0).toString()).contains("active=null");
    }

    @Test
    void locationBulkIngestWriter_nullJobId_skipsChunk() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");

        ItemWriter<NumberedRecord<LocationRecord>> writer = batchConfiguration.locationBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void locationBulkIngestWriter_nullLocationId_skipsChunk() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");

        ItemWriter<NumberedRecord<LocationRecord>> writer =
                batchConfiguration.locationBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void locationBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");
        location.setCode("CLT-SOUTH");

        ItemWriter<NumberedRecord<LocationRecord>> writer = batchConfiguration.locationBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<LocationRecord>> chunk = Chunk.of(numbered(0, location));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void locationBulkIngestWriter_skipsChunk_onMalformedJobId() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");

        ItemWriter<NumberedRecord<LocationRecord>> writer = batchConfiguration.locationBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void locationBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        LocationRecord location = new LocationRecord();
        location.setName("Charlotte South");
        location.setCode("CLT-SOUTH");

        ItemWriter<NumberedRecord<LocationRecord>> writer =
                batchConfiguration.locationBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, location)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "location:write");
    }

    // --- peopleBulkIngestWriter ---

    @Test
    void peopleBulkIngestWriter_happyPath_postsChunk() {
        PersonRecord employee = new PersonRecord();
        employee.setFirstName("Jane");
        employee.setLastName("Smith");
        employee.setEmployeeNumber("EMP-001");
        employee.setHireDate("2020-01-01");

        ItemWriter<NumberedRecord<PersonRecord>> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, employee)))).doesNotThrowAnyException();
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
        employee.setFirstName("Jane");
        employee.setLastName("Smith");

        ItemWriter<NumberedRecord<PersonRecord>> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, employee)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void peopleBulkIngestWriter_nullLocationId_skipsChunk() {
        PersonRecord employee = new PersonRecord();
        employee.setFirstName("Jane");
        employee.setLastName("Smith");

        ItemWriter<NumberedRecord<PersonRecord>> writer =
                batchConfiguration.peopleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, employee)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<BasePriceRecord>> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, priceEntry)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<BasePriceRecord>> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, priceEntry)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void priceBulkIngestWriter_nullLocationId_skipsChunk() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<NumberedRecord<BasePriceRecord>> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, priceEntry)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void vehicleBulkIngestWriter_nullLocationId_skipsChunk() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer =
                batchConfiguration.vehicleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- vehicleFitmentBulkIngestWriter ---

    @Test
    void vehicleFitmentBulkIngestWriter_happyPath_postsChunk() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");
        fitment.setManufacturerName("Bosch");
        fitment.setMakeName("Honda");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, null, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    @Test
    void vehicleFitmentBulkIngestWriter_nullLocationId_skipsChunk() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, null, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
        verify(mockRestClient, never()).post();
    }

    // --- RestClientException rethrow tests ---

    @Test
    void catalogBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<CatalogProductRecord>> chunk = Chunk.of(numbered(0, product));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void customerBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<CustomerPersonRecord>> chunk = Chunk.of(numbered(0, person));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void peopleBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        PersonRecord employee = new PersonRecord();
        employee.setFirstName("Jane");
        employee.setLastName("Smith");

        ItemWriter<NumberedRecord<PersonRecord>> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<PersonRecord>> chunk = Chunk.of(numbered(0, employee));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void priceBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<NumberedRecord<BasePriceRecord>> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<BasePriceRecord>> chunk = Chunk.of(numbered(0, priceEntry));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void vehicleBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<VehicleBulkRecord>> chunk = Chunk.of(numbered(0, vehicle));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    @Test
    void vehicleFitmentBulkIngestWriter_throwsRestClientException_onHttpFailure() {
        when(responseSpec.body(BulkIngestResponse.class)).thenThrow(new RestClientException("test error"));

        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setManufacturerName("Bosch");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);
        Chunk<NumberedRecord<VehicleFitmentRecord>> chunk = Chunk.of(numbered(0, fitment));

        assertThrows(RestClientException.class, () -> writer.write(chunk));
    }

    // --- Malformed UUID skip tests ---

    @Test
    void catalogBulkIngestWriter_skipsChunk_onMalformedJobId() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer = batchConfiguration.catalogBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void customerBulkIngestWriter_skipsChunk_onMalformedJobId() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer = batchConfiguration.customerBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, person)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void peopleBulkIngestWriter_skipsChunk_onMalformedJobId() {
        PersonRecord employee = new PersonRecord();
        employee.setFirstName("Jane");
        employee.setLastName("Smith");

        ItemWriter<NumberedRecord<PersonRecord>> writer = batchConfiguration.peopleBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, employee)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void priceBulkIngestWriter_skipsChunk_onMalformedJobId() {
        BasePriceRecord priceEntry = new BasePriceRecord();
        priceEntry.setProductId("prod-1");

        ItemWriter<NumberedRecord<BasePriceRecord>> writer = batchConfiguration.priceBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, priceEntry)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void vehicleBulkIngestWriter_skipsChunk_onMalformedJobId() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    @Test
    void vehicleFitmentBulkIngestWriter_skipsChunk_onMalformedJobId() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setManufacturerName("Bosch");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, "not-a-valid-uuid", VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
        verifyNoInteractions(mockRestClient);
    }

    // --- Type conversion error tests ---

    @Test
    void vehicleBulkIngestWriter_handlesInvalidAccountId_andYear() {
        VehicleBulkRecord vehicle = new VehicleBulkRecord();
        vehicle.setAccountId("not-a-uuid");
        vehicle.setYear("not-a-number");
        vehicle.setVin("1HGCM82633A004352");

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer = batchConfiguration.vehicleBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
        verify(mockRestClient).post();
    }

    @Test
    void vehicleFitmentBulkIngestWriter_handlesInvalidPartNumberId() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("not-a-long");
        fitment.setManufacturerName("Bosch");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, VALID_OPERATOR_ID);

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
        verify(mockRestClient).post();
    }

    // --- Blank operatorId sanitization (PRCR-001) ---

    @Test
    void catalogBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        CatalogProductRecord product = new CatalogProductRecord();
        product.setSku("SKU-001");
        product.setName("Product Name");

        ItemWriter<NumberedRecord<CatalogProductRecord>> writer =
                batchConfiguration.catalogBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, product)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "catalog:product:create");
    }

    @Test
    void customerBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        CustomerPersonRecord person = new CustomerPersonRecord();
        person.setFirstName("John");
        person.setLastName("Doe");

        ItemWriter<NumberedRecord<CustomerPersonRecord>> writer =
                batchConfiguration.customerBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, person)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "crm:party:create");
    }

    @Test
    void peopleBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        PersonRecord employee = new PersonRecord();
        employee.setFirstName("Jane");
        employee.setLastName("Smith");
        employee.setEmployeeNumber("EMP-001");
        employee.setHireDate("2020-01-01");

        ItemWriter<NumberedRecord<PersonRecord>> writer =
                batchConfiguration.peopleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, employee)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<BasePriceRecord>> writer =
                batchConfiguration.priceBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, priceEntry)))).doesNotThrowAnyException();
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

        ItemWriter<NumberedRecord<VehicleBulkRecord>> writer =
                batchConfiguration.vehicleBulkIngestWriter(restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, vehicle)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "vehicle-inventory:registry:create");
    }

    @Test
    void vehicleFitmentBulkIngestWriter_blankOperatorId_usesServiceFallbackUser() {
        VehicleFitmentRecord fitment = new VehicleFitmentRecord();
        fitment.setPartNumberId("12345");
        fitment.setManufacturerName("Bosch");
        fitment.setMakeName("Honda");

        ItemWriter<NumberedRecord<VehicleFitmentRecord>> writer = batchConfiguration.vehicleFitmentBulkIngestWriter(
                restClientBuilder, VALID_JOB_ID, VALID_LOCATION_ID, "   ");

        assertThatCode(() -> writer.write(Chunk.of(numbered(0, fitment)))).doesNotThrowAnyException();
        verify(requestBodySpec).header("X-User", "bulk-loader-service");
        verify(requestBodySpec).header("X-Authorities", "vehicle-fitment:hint:create");
    }
}
