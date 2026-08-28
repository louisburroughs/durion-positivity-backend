package com.positivity.location.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.StorageLocationBulkIngestRecord;
import com.positivity.location.internal.dto.StorageLocationPatchRequest;
import com.positivity.location.internal.dto.StorageLocationRequest;
import com.positivity.location.internal.dto.StorageLocationResponse;
import com.positivity.location.internal.dto.StorageLocationTopologyResponse;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.StorageLocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Creates a site's storage topology in bulk.
 *
 * <p>Rows are processed in the order given, because a location may name its parent by name and that
 * parent may be created earlier in the same batch — a file can describe a shelf and its bins in one
 * call. Names already present at the site are skipped rather than failed, so re-running a topology
 * file converges instead of erroring on every row.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:write"})
@RequestMapping("/v1/locations/storage-locations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Storage Location Bulk Ingest API", description = "Bulk import a site's storage topology")
public class StorageLocationBulkIngestController extends AbstractBulkIngestController<StorageLocationBulkIngestRecord> {

    private static final String INGEST_FAILED = "STORAGE_LOCATION_INGEST_FAILED";
    private static final String UNRESOLVED_PARENT = "STORAGE_LOCATION_PARENT_UNRESOLVED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"name":"Parts Shelf A","type":"SHELF","storageCategoryCode":"GENERAL"},
               {"name":"Bin A-01","type":"BIN","parentName":"Parts Shelf A",
                "storageCategoryCode":"SMALL_PARTS_BIN","maxUnitCount":500}
             ]}
            """;

    private final StorageLocationService storageLocationService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @EmitEvent(id = "LOCATION_STORAGE_LOCATION_BULK_INGEST", apiVersion = "1")
    @Operation(
            operationId = "bulkIngestStorageLocations",
            summary = "Create a Site's Storage Topology in Bulk",
            description = """
                    Creates many storage locations at once, resolving each row's parent by name and applying a \
                    non-default status as a follow-up update.
                    Use this tool when commissioning a site or seeding an environment; use createStorageLocation \
                    instead for a single location, and patchStorageLocation to change one that already exists.
                    Preconditions: each row's site must exist, and a row naming a parent must either follow that \
                    parent in the same batch or name one already at the site.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a name and a type; a \
                    record's own siteId overrides the batch one.
                    Emits a LOCATION_STORAGE_LOCATION_BULK_INGEST event, and a storage-location fact per created \
                    row, which is what hydrates the pos-inventory replica.
                    Re-running the same file is safe: a name already present at its site is skipped, not failed, \
                    and an existing location is never modified — applying a changed status or capacity to a live \
                    location is an operator's update, not a reseed.
                    Returns 200 with a per-record result; check each result rather than the status alone.
                    """)
    @ApiResponse(
            responseCode = "200",
            description = "Batch processed; inspect per-record results",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = BulkIngestResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @ApiResponse(responseCode = "403", description = "Forbidden")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Storage locations to create, parents before their children.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Shelf and bins",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<StorageLocationBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<StorageLocationBulkIngestRecord> request) {

        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        // name -> id per site, seeded from what is already there and extended as rows are created,
        // so a parent named in this batch resolves without a round trip per row.
        Map<UUID, Map<String, UUID>> idsBySite = new HashMap<>();

        for (int i = 0; i < request.getRecords().size(); i++) {
            StorageLocationBulkIngestRecord record = request.getRecords().get(i);
            UUID siteId = record.getSiteId() == null ? request.getLocationId() : record.getSiteId();
            try {
                Map<String, UUID> idsByName = idsBySite.computeIfAbsent(siteId, this::existingNames);
                results.add(create(i, record, siteId, idsByName));
                successCount++;
            } catch (UnresolvedParentException exception) {
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(UNRESOLVED_PARENT)
                        .errorMessage(exception.getMessage())
                        .build());
                failureCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest storage location at row {}: {}", i, exception.getMessage());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode(INGEST_FAILED)
                        .errorMessage(errorMessage(exception))
                        .build());
                failureCount++;
            }
        }

        return BulkIngestResponse.builder()
                .totalSubmitted(request.getRecords().size())
                .successCount(successCount)
                .failureCount(failureCount)
                .results(results)
                .build();
    }

    private BulkIngestResult create(
            int rowIndex, StorageLocationBulkIngestRecord record, UUID siteId, Map<String, UUID> idsByName) {

        UUID existing = idsByName.get(key(record.getName()));
        if (existing != null) {
            // Already there. Reported as a success with its id so a caller can still resolve the
            // name, and deliberately not updated: an operator who retuned a capacity on a live
            // location keeps it, and a fixture change is applied by an update, not a reseed.
            return BulkIngestResult.builder()
                    .rowIndex(rowIndex)
                    .entityId(existing)
                    .success(true)
                    .build();
        }

        StorageLocationRequest createRequest = StorageLocationRequest.builder()
                .name(record.getName())
                .type(record.getType())
                .storageCategoryCode(record.getStorageCategoryCode())
                .hazardContainment(record.getHazardContainment())
                .allowNewProduct(record.getAllowNewProduct())
                .parentStorageLocationId(resolveParent(record, idsByName))
                // Accepted on create, unlike status, so this costs no follow-up call.
                .capacity(capacityOf(record))
                .build();

        StorageLocationResponse created = storageLocationService.createStorageLocation(siteId, createRequest);
        idsByName.put(key(record.getName()), created.getId());

        applyStatus(siteId, created.getId(), record);

        return BulkIngestResult.builder()
                .rowIndex(rowIndex)
                .entityId(created.getId())
                .success(true)
                .build();
    }

    /**
     * Creation always produces an ACTIVE location, so a row asking for anything else needs a second
     * call. Failing that call fails the row: a location left ACTIVE when the file said INACTIVE is
     * a destination putaway will happily route to.
     */
    private void applyStatus(UUID siteId, UUID storageLocationId, StorageLocationBulkIngestRecord record) {
        if (record.getStatus() == null || record.getStatus() == StorageLocationStatus.ACTIVE) {
            return;
        }
        storageLocationService.patchStorageLocation(
                siteId,
                storageLocationId,
                StorageLocationPatchRequest.builder().status(record.getStatus()).build());
    }

    private Map<String, Object> capacityOf(StorageLocationBulkIngestRecord record) {
        return record.getMaxUnitCount() == null ? null : Map.of("maxUnitCount", record.getMaxUnitCount());
    }

    private UUID resolveParent(StorageLocationBulkIngestRecord record, Map<String, UUID> idsByName) {
        if (record.getParentName() == null || record.getParentName().isBlank()) {
            return null;
        }
        UUID parentId = idsByName.get(key(record.getParentName()));
        if (parentId == null) {
            // Creating it parentless would put the location at the top of the topology, which reads
            // as a successful load and quietly changes where putaway can route.
            throw new UnresolvedParentException(record.getParentName());
        }
        return parentId;
    }

    /** The site's existing locations, in every status: a name is taken whether or not it is active. */
    private Map<String, UUID> existingNames(UUID siteId) {
        Map<String, UUID> idsByName = new HashMap<>();
        for (StorageLocationTopologyResponse existing : storageLocationService.listStorageLocationTopology(siteId)) {
            idsByName.put(key(existing.getName()), existing.getId());
        }
        return idsByName;
    }

    private String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private String errorMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Storage location ingest failed" : message;
    }

    /** Signals a parent name that matched nothing, so the row reports that rather than a generic failure. */
    private static class UnresolvedParentException extends RuntimeException {
        UnresolvedParentException(String parentName) {
            super("Parent storage location '" + parentName + "' does not exist at this site"
                    + " and was not created earlier in this batch");
        }
    }
}
