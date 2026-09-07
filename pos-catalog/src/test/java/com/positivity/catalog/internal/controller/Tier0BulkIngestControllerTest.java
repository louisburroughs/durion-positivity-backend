package com.positivity.catalog.internal.controller;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.catalog.config.TestSecurityConfig;
import com.positivity.catalog.internal.dto.CatalogItemResponseDto;
import com.positivity.catalog.internal.dto.CatalogServiceBulkIngestRecord;
import com.positivity.catalog.internal.dto.ServiceLaborStandardImportRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardResponseDto;
import com.positivity.catalog.internal.dto.ServicePackageBulkIngestRecord;
import com.positivity.catalog.internal.dto.ServicePackageMemberBulkIngestRecord;
import com.positivity.catalog.internal.dto.ServicePackageResponseDto;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.service.CatalogService;
import com.positivity.catalog.internal.service.ServiceLaborStandardService;
import com.positivity.catalog.internal.service.ServicePackageService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/**
 * The four Tier 0 bulk-ingest endpoints (#1575; docs/DATA_SEED_STRATEGY.md §3 Tier 2).
 *
 * <p>What is worth testing here is the contract every bulk endpoint shares and no single-item
 * endpoint has: a bad row fails alone while the batch proceeds, a rejection carries the reason the
 * caller needs, and a server-side fault carries a correlation id and none of the exception's text
 * (issue #1718). The per-endpoint upsert rules are covered against the services themselves.
 */
@WebMvcTest({
    CatalogServiceBulkIngestController.class,
    ServiceLaborStandardBulkIngestController.class,
    ServicePackageBulkIngestController.class,
    ServicePackageMemberBulkIngestController.class
})
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class Tier0BulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");
    private static final UUID ENTITY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    java.time.Clock clock;

    @MockitoBean
    org.springframework.cache.CacheManager cacheManager;

    @MockitoBean
    CatalogService catalogService;

    @MockitoBean
    ServiceLaborStandardService laborStandardService;

    @MockitoBean
    ServicePackageService servicePackageService;

    @Test
    @DisplayName("services: a batch reports one verdict per row")
    void servicesBatchReportsPerRowVerdicts() throws Exception {
        CatalogItemResponseDto created = new CatalogItemResponseDto();
        created.setId(ENTITY_ID);
        when(catalogService.upsertServiceByOperationCode(any())).thenReturn(created);

        mockMvc.perform(post("/v1/catalog/services/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(
                                List.of(serviceRecord("TPMS-SENSOR-SERVICE"), serviceRecord("LUG-TORQUE-RECHECK"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSubmitted").value(2))
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(0))
                .andExpect(jsonPath("$.results[0].entityId").value(ENTITY_ID.toString()));
    }

    @Test
    @DisplayName("services: unparseable hours fail their own row and name the field")
    void servicesUnparseableHoursFailOneRow() throws Exception {
        CatalogItemResponseDto created = new CatalogItemResponseDto();
        created.setId(ENTITY_ID);
        when(catalogService.upsertServiceByOperationCode(any())).thenReturn(created);

        CatalogServiceBulkIngestRecord bad = serviceRecord("LUG-TORQUE-RECHECK");
        bad.setDefaultLaborHours("about twenty minutes");

        mockMvc.perform(post("/v1/catalog/services/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                batch(List.of(serviceRecord("TPMS-SENSOR-SERVICE"), bad)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[1].rowIndex").value(1))
                .andExpect(jsonPath("$.results[1].errorCode").value("CATALOG_SERVICE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].errorMessage", containsString("defaultLaborHours")));
    }

    @Test
    @DisplayName("services: a server-side fault gives a correlation id and none of the exception's text")
    void servicesServerFaultIsReportedGenerically() throws Exception {
        when(catalogService.upsertServiceByOperationCode(any()))
                .thenThrow(new IllegalStateException("could not execute statement [insert into service ...]"));

        mockMvc.perform(post("/v1/catalog/services/bulk-ingest")
                        .header("X-Correlation-Id", "corr-from-caller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(serviceRecord("TPMS-SENSOR-SERVICE"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.results[0].correlationId").value("corr-from-caller"))
                .andExpect(jsonPath("$.results[0].errorMessage", not(containsString("insert into service"))));
    }

    @Test
    @DisplayName("labor standards: an operation the catalog does not know fails only its own row")
    void laborStandardUnknownOperationFailsOneRow() throws Exception {
        ServiceLaborStandardResponseDto applied = new ServiceLaborStandardResponseDto();
        applied.setId(ENTITY_ID);
        when(laborStandardService.importStandard(eq("FLEET-PM-A-SERVICE"), any()))
                .thenReturn(applied);
        when(laborStandardService.importStandard(eq("NO-SUCH-OP"), any()))
                .thenThrow(new CatalogNotFoundException("No service with operation code NO-SUCH-OP"));

        mockMvc.perform(post("/v1/catalog/labor-standards/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                batch(List.of(standardRecord("FLEET-PM-A-SERVICE"), standardRecord("NO-SUCH-OP"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[1].errorCode").value("LABOR_STANDARD_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].errorMessage", containsString("NO-SUCH-OP")));
    }

    @Test
    @DisplayName("packages: text columns become the typed fields the authoring DTO expects")
    void packageRecordIsParsedIntoTheAuthoringShape() throws Exception {
        ServicePackageResponseDto saved = new ServicePackageResponseDto();
        saved.setId(ENTITY_ID);
        when(servicePackageService.upsert(any())).thenReturn(saved);

        ServicePackageBulkIngestRecord record = new ServicePackageBulkIngestRecord();
        record.setPackageCode("TIRE-INSTALL-PKG-4");
        record.setName("Four Tire Installation Package");
        record.setPackageLaborHours("1.6");
        record.setActive("true");
        record.setEffectiveFrom("2026-01-01");

        mockMvc.perform(post("/v1/service-packages/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(record)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(ENTITY_ID.toString()));
    }

    @Test
    @DisplayName("packages: a flag that says neither true nor false is refused, never read as false")
    void packageActiveFlagMustSayTrueOrFalse() throws Exception {
        ServicePackageBulkIngestRecord record = new ServicePackageBulkIngestRecord();
        record.setPackageCode("TIRE-INSTALL-PKG-4");
        record.setName("Four Tire Installation Package");
        record.setPackageLaborHours("1.6");
        record.setActive("yes");

        mockMvc.perform(post("/v1/service-packages/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(record)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("SERVICE_PACKAGE_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[0].errorMessage", containsString("true or false")));
    }

    @Test
    @DisplayName("members: both sides are addressed by code")
    void memberRecordIsAddressedByCodes() throws Exception {
        ServicePackageResponseDto saved = new ServicePackageResponseDto();
        saved.setId(ENTITY_ID);
        when(servicePackageService.upsertMember(eq("TIRE-INSTALL-PKG-4"), eq("WHEEL-BALANCE-SET-4"), any()))
                .thenReturn(saved);

        ServicePackageMemberBulkIngestRecord record = new ServicePackageMemberBulkIngestRecord();
        record.setPackageCode("TIRE-INSTALL-PKG-4");
        record.setOperationCode("WHEEL-BALANCE-SET-4");
        record.setSequence("20");
        record.setQuantity("1.00");
        record.setRequired("true");

        mockMvc.perform(post("/v1/service-package-members/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(batch(List.of(record)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
    }

    private static CatalogServiceBulkIngestRecord serviceRecord(String operationCode) {
        CatalogServiceBulkIngestRecord record = new CatalogServiceBulkIngestRecord();
        record.setOperationCode(operationCode);
        record.setName(operationCode);
        record.setOperationCategory("TIRE_SERVICE");
        record.setDefaultLaborHours("0.6");
        return record;
    }

    private static ServiceLaborStandardImportRequestDto standardRecord(String operationCode) {
        ServiceLaborStandardImportRequestDto record = new ServiceLaborStandardImportRequestDto();
        record.setOperationCode(operationCode);
        record.setSourceCode("DURION");
        record.setSourceRevision("tier0-fake-2026-09");
        record.setLaborHours("1.4");
        record.setTimeType("DURION_STANDARD");
        return record;
    }

    private static <T> BulkIngestRequest<T> batch(List<T> records) {
        BulkIngestRequest<T> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setRecords(records);
        return request;
    }
}
