package com.positivity.location.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.MobileUnitBulkIngestRecord;
import com.positivity.location.internal.dto.MobileUnitRequest;
import com.positivity.location.internal.dto.MobileUnitResponse;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.MobileUnitService;
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
 * Creates mobile units in bulk.
 *
 * <p>A name is unique per base location and the service answers a duplicate with 409, treated here
 * as "already there" so a re-run converges.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:mobile-unit:manage"})
@RequestMapping("/v1/mobile-units")
@RequiredArgsConstructor
@Tag(name = "Mobile Unit Bulk Ingest API", description = "Bulk import mobile units")
public class MobileUnitBulkIngestController extends AbstractBulkIngestController<MobileUnitBulkIngestRecord> {

    private static final String INGEST_FAILED = "MOBILE_UNIT_INGEST_FAILED";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a00",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a10",
             "operatorId":"seed-operator",
             "records":[
               {"name":"Van 01","status":"INACTIVE"},
               {"name":"Van 02","status":"INACTIVE","notes":"Mobile tyre fitting"}
             ]}
            """;

    private final MobileUnitService mobileUnitService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + LocationPermissions.MOBILE_UNIT_MANAGE + "')")
    @EmitEvent(id = "LOCATION_MOBILE_UNIT_MANAGE", apiVersion = "1")
    @Operation(operationId = "bulkIngestMobileUnits", summary = "Create Mobile Units in Bulk", description = """
                    Creates many mobile units at once, one per record.
                    Use this tool when commissioning a fleet or seeding an environment; use createMobileUnit \
                    instead for a single unit.
                    Preconditions: each row's base location must exist. A unit created ACTIVE must also carry a \
                    travel buffer policy, capabilities and coverage rules, which this record does not express — \
                    so seed units INACTIVE and activate them through createMobileUnit or a later update.
                    Required inputs: jobId (UUID), locationId (UUID) and records, each with a name; a record's own \
                    baseLocationId overrides the batch one.
                    Emits a LOCATION_MOBILE_UNIT_MANAGE event and a unit-created event per row.
                    Re-running the same file is safe: a name already present at its base location is reported as \
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
                            description = "Mobile units to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Mobile units", value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    BulkIngestRequest<MobileUnitBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<MobileUnitBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            MobileUnitBulkIngestRecord record = request.getRecords().get(i);
            try {
                MobileUnitResponse created = mobileUnitService.createMobileUnit(toRequest(record, request));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (ResponseStatusException exception) {
                if (exception.getStatusCode() == HttpStatus.CONFLICT) {
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

    private MobileUnitRequest toRequest(
            MobileUnitBulkIngestRecord record, BulkIngestRequest<MobileUnitBulkIngestRecord> request) {
        UUID baseLocationId = record.getBaseLocationId() == null ? request.getLocationId() : record.getBaseLocationId();
        MobileUnitRequest unitRequest = new MobileUnitRequest();
        unitRequest.setName(record.getName());
        unitRequest.setBaseLocationId(baseLocationId);
        unitRequest.setStatus(record.getStatus());
        unitRequest.setNotes(record.getNotes());
        return unitRequest;
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
        return "Mobile unit ingest failed";
    }
}
