package com.positivity.location.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.BayBulkIngestRecord;
import com.positivity.location.internal.dto.BayCapacityRequest;
import com.positivity.location.internal.dto.BayRequest;
import com.positivity.location.internal.dto.BayResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.BayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Creates service bays in bulk.
 *
 * <p>Bay names are unique per location and the service answers a duplicate with 409, which this
 * treats as "already there" rather than as a failure — commissioning the same file twice should
 * converge, not report twenty errors.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:bay:manage"})
@RequestMapping("/v1/locations/bays")
@RequiredArgsConstructor
@Tag(name = "Bay Bulk Ingest API", description = "Bulk import service bays")
public class BayBulkIngestController extends AbstractBulkIngestController<BayBulkIngestRecord> {

    private static final String INGEST_FAILED = "BAY_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"name":"Bay 1","bayType":"GENERAL_SERVICE","maxConcurrentVehicles":1},
               {"name":"Alignment Bay","bayType":"ALIGNMENT","maxConcurrentVehicles":1}
             ]}
            """;

    private final BayService bayService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + LocationPermissions.BAY_MANAGE + "')")
    @EmitEvent(id = "LOCATION_BAY_BULK_INGEST", apiVersion = "1")
    @Operation(operationId = "bulkIngestBays", summary = "Create Service Bays in Bulk", description = """
                    Creates many service bays at once, one per record.
                    Use this tool when commissioning a location or seeding an environment; use createBay instead \
                    for a single bay.
                    Preconditions: each row's location must exist, and bayType must name a known bay type.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a name, a bayType and \
                    maxConcurrentVehicles; a record's own locationId overrides the batch one.
                    Emits a LOCATION_BAY_BULK_INGEST event and a bay-created event per row.
                    Re-running the same file is safe: a bay name already present at its location is reported as \
                    already existing rather than as a failure.
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
                            description = "Service bays to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Bays", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<BayBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<BayBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            BayBulkIngestRecord record = request.getRecords().get(i);
            UUID locationId = record.getLocationId() == null ? request.getLocationId() : record.getLocationId();
            try {
                BayResponse created = bayService.createBay(locationId, toBayRequest(record));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode() == HttpStatus.CONFLICT) {
                    // The bay is already there, which is the desired state.
                    results.add(
                            BulkIngestResult.builder().rowIndex(i).success(true).build());
                    successCount++;
                    continue;
                }
                results.add(rowFailure(i, exception));
                failureCount++;
            } catch (Exception exception) {
                results.add(rowFailure(i, exception));
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

    private BayRequest toBayRequest(BayBulkIngestRecord record) {
        BayRequest bayRequest = new BayRequest();
        bayRequest.setName(record.getName());
        bayRequest.setBayType(record.getBayType());
        BayCapacityRequest capacity = new BayCapacityRequest();
        capacity.setMaxConcurrentVehicles(record.getMaxConcurrentVehicles());
        bayRequest.setCapacity(capacity);
        if (record.getStatus() != null && !record.getStatus().isBlank()) {
            bayRequest.setStatus(record.getStatus());
        }
        return bayRequest;
    }

    /**
     * No module-owned types to name: this module\'s two domain exceptions,
     * {@code ResourceNotFoundException} and {@code DuplicateResourceException}, carry
     * {@code @ResponseStatus} 404 and 409 of their own, and its services otherwise refuse a row
     * with a {@code ResponseStatusException}. {@link com.positivity.bulkingest.BulkIngestFailures}
     * recognises both platform-wide, so a rejected row keeps its message with no list here.
     * Everything else — including the bare {@code IllegalArgumentException} these services still
     * raise in places, which is equally what Hibernate raises — is a server-side fault and is
     * reported generically against a correlation id (issue #1718).
     */
    @Override
    protected String rowRejectionCode() {
        return INGEST_FAILED;
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Bay ingest failed";
    }
}
