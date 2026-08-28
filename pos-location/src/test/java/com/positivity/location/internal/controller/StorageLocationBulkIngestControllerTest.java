package com.positivity.location.internal.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.location.config.TestSecurityConfig;
import com.positivity.location.internal.dto.StorageLocationBulkIngestRecord;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.service.StorageLocationService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
 * The storage-topology ingest, whose value is entirely in the three things a caller cannot do for
 * itself: resolve a parent named in the same batch, apply a status the create path cannot express,
 * and recognise what is already there.
 */
@WebMvcTest(StorageLocationBulkIngestController.class)
@Import(TestSecurityConfig.class)
@ActiveProfiles("test")
@SuppressWarnings({"java:S6813", "java:S100", "java:S1192"})
class StorageLocationBulkIngestControllerTest {

    private static final String PATH = "/v1/locations/storage-locations/bulk-ingest";
    private static final UUID JOB_ID = UUID.fromString("00000000-0000-0000-0000-000000000050");
    private static final UUID SITE_ID = UUID.fromString("00000000-0000-0000-0000-000000000051");
    private static final UUID SHELF_ID = UUID.fromString("00000000-0000-0000-0000-000000000052");
    private static final UUID BIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000053");

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @MockitoBean
    StorageLocationService storageLocationService;

    @BeforeEach
    void siteIsEmpty() {
        when(storageLocationService.listStorageLocationTopology(any())).thenReturn(List.of());
    }

    private static StorageLocationBulkIngestRecord record(String name, StorageLocationType type, String parentName) {
        StorageLocationBulkIngestRecord record = new StorageLocationBulkIngestRecord();
        record.setName(name);
        record.setType(type);
        record.setParentName(parentName);
        return record;
    }

    private BulkIngestRequest<StorageLocationBulkIngestRecord> request(List<StorageLocationBulkIngestRecord> records) {
        BulkIngestRequest<StorageLocationBulkIngestRecord> request = new BulkIngestRequest<>();
        request.setJobId(JOB_ID);
        request.setLocationId(SITE_ID);
        request.setOperatorId("seed-operator");
        request.setRecords(records);
        return request;
    }

    private String json(BulkIngestRequest<StorageLocationBulkIngestRecord> request) {
        return objectMapper.writeValueAsString(request);
    }

    private static StorageLocationResponse response(UUID id, String name) {
        return StorageLocationResponse.builder().id(id).name(name).build();
    }

    @Test
    void bulkIngest_resolvesAParentCreatedEarlierInTheSameBatch() throws Exception {
        // The point of a batch: a file can describe a shelf and its bins in one call.
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenReturn(response(SHELF_ID, "Parts Shelf A"), response(BIN_ID, "Bin A-01"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(List.of(
                                record("Parts Shelf A", StorageLocationType.SHELF, null),
                                record("Bin A-01", StorageLocationType.BIN, "Parts Shelf A"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(2))
                .andExpect(jsonPath("$.failureCount").value(0));

        verify(storageLocationService)
                .createStorageLocation(
                        eq(SITE_ID),
                        org.mockito.ArgumentMatchers.argThat(
                                (StorageLocationRequest req) -> SHELF_ID.equals(req.getParentStorageLocationId())));
    }

    @Test
    void bulkIngest_resolvesAParentThatAlreadyExistsAtTheSite() throws Exception {
        when(storageLocationService.listStorageLocationTopology(SITE_ID))
                .thenReturn(List.of(StorageLocationTopologyResponse.builder()
                        .id(SHELF_ID)
                        .name("Parts Shelf A")
                        .build()));
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any())).thenReturn(response(BIN_ID, "Bin A-01"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(List.of(record("Bin A-01", StorageLocationType.BIN, "Parts Shelf A"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));
    }

    @Test
    void bulkIngest_unresolvedParent_failsTheRowRatherThanCreatingItParentless() throws Exception {
        // Creating it parentless would put the location at the top of the topology, which reads as
        // a successful load and quietly changes where putaway can route.
        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(List.of(record("Bin A-01", StorageLocationType.BIN, "No Such Shelf"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("STORAGE_LOCATION_PARENT_UNRESOLVED"));

        verify(storageLocationService, never()).createStorageLocation(any(), any());
    }

    @Test
    void bulkIngest_existingNameIsSkipped_andNeverModified() throws Exception {
        // An operator who retuned a capacity on a live location keeps it; a fixture change is
        // applied by an update, not by a reseed.
        when(storageLocationService.listStorageLocationTopology(SITE_ID))
                .thenReturn(List.of(StorageLocationTopologyResponse.builder()
                        .id(BIN_ID)
                        .name("Bin A-01")
                        .build()));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(List.of(record("Bin A-01", StorageLocationType.BIN, null))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.results[0].entityId").value(BIN_ID.toString()));

        verify(storageLocationService, never()).createStorageLocation(any(), any());
        verify(storageLocationService, never()).patchStorageLocation(any(), any(), any());
    }

    @Test
    void bulkIngest_appliesANonActiveStatusAsAFollowUp() throws Exception {
        // Creation always produces an ACTIVE location, so INACTIVE needs a second call — and a bin
        // left ACTIVE when the file said otherwise is a destination putaway will route to.
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenReturn(response(BIN_ID, "Retired Bin"));

        StorageLocationBulkIngestRecord retired = record("Retired Bin", StorageLocationType.BIN, null);
        retired.setStatus(StorageLocationStatus.INACTIVE);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json(request(List.of(retired)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1));

        verify(storageLocationService)
                .patchStorageLocation(
                        eq(SITE_ID),
                        eq(BIN_ID),
                        org.mockito.ArgumentMatchers.argThat((StorageLocationPatchRequest patch) ->
                                patch.getStatus() == StorageLocationStatus.INACTIVE));
    }

    @Test
    void bulkIngest_activeStatusNeedsNoFollowUp() throws Exception {
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any())).thenReturn(response(BIN_ID, "Bin A-01"));

        StorageLocationBulkIngestRecord active = record("Bin A-01", StorageLocationType.BIN, null);
        active.setStatus(StorageLocationStatus.ACTIVE);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json(request(List.of(active)))))
                .andExpect(status().isOk());

        verify(storageLocationService, never()).patchStorageLocation(any(), any(), any());
    }

    @Test
    void bulkIngest_sendsCapacityOnTheCreate() throws Exception {
        // The create path does accept capacity, so carrying it there saves a second call per row.
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any())).thenReturn(response(BIN_ID, "Bin A-01"));

        StorageLocationBulkIngestRecord capped = record("Bin A-01", StorageLocationType.BIN, null);
        capped.setMaxUnitCount(500);

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json(request(List.of(capped)))))
                .andExpect(status().isOk());

        verify(storageLocationService)
                .createStorageLocation(
                        eq(SITE_ID),
                        org.mockito.ArgumentMatchers.argThat((StorageLocationRequest req) -> req.getCapacity() != null
                                && Integer.valueOf(500).equals(req.getCapacity().get("maxUnitCount"))));
    }

    @Test
    void bulkIngest_oneFailingRowDoesNotStopTheRest() throws Exception {
        when(storageLocationService.createStorageLocation(eq(SITE_ID), any()))
                .thenThrow(new IllegalStateException("DUPLICATE_BARCODE"))
                .thenReturn(response(BIN_ID, "Bin A-02"));

        mockMvc.perform(post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request(List.of(
                                record("Bin A-01", StorageLocationType.BIN, null),
                                record("Bin A-02", StorageLocationType.BIN, null))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.successCount").value(1))
                .andExpect(jsonPath("$.failureCount").value(1))
                .andExpect(jsonPath("$.results[0].errorCode").value("STORAGE_LOCATION_INGEST_FAILED"))
                .andExpect(jsonPath("$.results[1].success").value(true));
    }
}
