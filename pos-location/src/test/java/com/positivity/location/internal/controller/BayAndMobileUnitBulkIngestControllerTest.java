package com.positivity.location.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.dto.BayBulkIngestRecord;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.dto.MobileUnitBulkIngestRecord;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.service.BayService;
import com.positivity.location.internal.service.MobileUnitService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/**
 * Bays and mobile units share one behaviour worth pinning: the owning service answers a duplicate
 * name with 409, and commissioning the same file twice must converge rather than report an error
 * for every row.
 */
@WebMvcTest({BayBulkIngestController.class, MobileUnitBulkIngestController.class})
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class BayAndMobileUnitBulkIngestControllerTest {

    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000060");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000061");
    private static final UUID ENTITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000062");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    BayService bayService;

    @MockitoBean
    MobileUnitService mobileUnitService;

    private <T> BulkIngestRequest<T> request(List<T> records) {
        BulkIngestRequest<T> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(LOCATION_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(records);
        return request;
    }

    private static BayBulkIngestRecord bay(String name) {
        BayBulkIngestRecord record = new BayBulkIngestRecord();
        record.setName(name);
        record.setBayType("GENERAL_SERVICE");
        record.setMaxConcurrentVehicles(1);
        return record;
    }

    private static MobileUnitBulkIngestRecord unit(String name) {
        MobileUnitBulkIngestRecord record = new MobileUnitBulkIngestRecord();
        record.setName(name);
        record.setStatus("INACTIVE");
        return record;
    }

    @Test
    void bays_createEachRow_andPopulateTheNestedCapacity() throws Exception {
        when(bayService.createBay(eq(LOCATION_ID), any()))
                .thenReturn(BayResponse.builder().id(ENTITY_ID).build());

        mockMvc.perform(post("/v1/locations/bays/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(bay("Bay 1"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(ENTITY_ID.toString()));

        // BayRequest carries maxConcurrentVehicles twice; the nested one is what the service reads.
        org.mockito.Mockito.verify(bayService)
                .createBay(
                        eq(LOCATION_ID),
                        org.mockito.ArgumentMatchers.argThat((BayRequest req) -> req.getCapacity() != null
                                && Integer.valueOf(1).equals(req.getCapacity().getMaxConcurrentVehicles())));
    }

    @Test
    void bays_duplicateNameIsAlreadyThere_notAFailure() throws Exception {
        when(bayService.createBay(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_NAME"));

        mockMvc.perform(post("/v1/locations/bays/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(bay("Bay 1"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }

    @Test
    void bays_otherServiceErrorsStillFailTheRow() throws Exception {
        when(bayService.createBay(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "LOCATION_NOT_FOUND"));

        mockMvc.perform(post("/v1/locations/bays/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(bay("Bay 1"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("BAY_INGEST_FAILED"));
    }

    @Test
    void mobileUnits_defaultTheBaseLocationToTheBatchLocation() throws Exception {
        when(mobileUnitService.createMobileUnit(any(MobileUnitRequest.class)))
                .thenReturn(MobileUnitResponse.builder().id(ENTITY_ID).build());

        mockMvc.perform(post("/v1/mobile-units/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(unit("Van 01"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        org.mockito.Mockito.verify(mobileUnitService)
                .createMobileUnit(org.mockito.ArgumentMatchers.argThat(
                        (MobileUnitRequest req) -> LOCATION_ID.equals(req.getBaseLocationId())));
    }

    @Test
    void mobileUnits_duplicateNameIsAlreadyThere_notAFailure() throws Exception {
        when(mobileUnitService.createMobileUnit(any(MobileUnitRequest.class)))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "DUPLICATE_NAME"));

        mockMvc.perform(post("/v1/mobile-units/bulk-ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request(List.of(unit("Van 01"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(0));
    }
}
