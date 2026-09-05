package com.positivity.vehicle.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.shared.error.ApiError;
import com.positivity.vehicle.internal.dto.VehicleBulkIngestRecord;
import com.positivity.vehicle.internal.exception.VehicleValidationException;
import com.positivity.vehicle.internal.exception.VehicleVinConflictException;
import com.positivity.vehicle.internal.security.VehicleInventoryPermissions;
import com.positivity.vehicle.internal.service.VehicleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"vehicle-inventory:registry:create"})
@RequestMapping("/v1/vehicles")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.REGISTRY_CREATE + "')")
@Tag(name = "Vehicle Bulk Ingest API", description = "Bulk import vehicle records")
public class VehicleBulkIngestController extends AbstractBulkIngestController<VehicleBulkIngestRecord> {

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "operatorId":"user-jdoe",
             "records":[
               {"accountId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                "vin":"1HGCM82633A004352",
                "unitNumber":"UNIT-1042",
                "description":"2003 Honda Accord EX Sedan",
                "licensePlate":"ABC1234",
                "licensePlateJurisdiction":"CA",
                "year":2003,
                "make":"Honda",
                "model":"Accord",
                "trim":"EX"}]}
            """;

    private final VehicleService vehicleService;

    @Operation(operationId = "bulkIngestVehicles", summary = "Bulk ingest vehicle records", description = """
                    Ingests a batch of vehicle records into the registry, creating each row independently through \
                    the same validation as createVehicle and reporting a per-row outcome.
                    Use this tool for bulk loads such as fleet imports; do not use createVehicle, which registers \
                    one vehicle per call, and do not retry a whole batch blindly, because rows that already \
                    succeeded would then fail as duplicate VINs.
                    Preconditions: the caller must hold the vehicle-inventory:registry:create authority, and each \
                    row's VIN must be valid and not already held by an active vehicle for that row to succeed.
                    Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array whose entries \
                    require accountId (UUID), vin (17 characters), unitNumber and description; licensePlate, \
                    licensePlateJurisdiction, year, make, model and trim are optional per record, and operatorId \
                    is optional on the envelope.
                    Emits a VEHICLE_BULK_INGEST event, and every successfully created row also queues a \
                    vehicle.vehicle.updated fact on the vehicle.events.v1 outbox for downstream replicas.
                    Returns 200 with per-row results even when some rows fail, since row failures carry an error \
                    code instead of an error status: VEHICLE_INGEST_FAILED for a row the registry rejected, whose \
                    errorMessage names what is wrong with that row, or INGEST_INTERNAL_ERROR for a row lost to a \
                    server-side fault, whose errorMessage carries only a correlationId to quote. Returns 400 with a \
                    VALIDATION_FAILED ApiError when the envelope is missing jobId or locationId or the records array \
                    is empty.
                    """)
    @ApiResponse(responseCode = "200", description = "Batch processed; check per-record success and failure counts.")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request envelope.",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + VehicleInventoryPermissions.REGISTRY_CREATE + "')")
    @EmitEvent(id = "VEHICLE_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch envelope carrying the ingest job, target location and the vehicle"
                                    + " records to create.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Single-record batch",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<VehicleBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<VehicleBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            VehicleBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                CreateVehicleRequest createRequest = CreateVehicleRequest.builder()
                        .accountId(ingestRecord.getAccountId())
                        .vin(ingestRecord.getVin())
                        .unitNumber(ingestRecord.getUnitNumber())
                        .description(ingestRecord.getDescription())
                        .licensePlate(ingestRecord.getLicensePlate())
                        .licensePlateJurisdiction(ingestRecord.getLicensePlateJurisdiction())
                        .year(ingestRecord.getYear())
                        .make(ingestRecord.getMake())
                        .model(ingestRecord.getModel())
                        .trim(ingestRecord.getTrim())
                        .build();

                var created = vehicleService.createVehicle(createRequest);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getVehicleId())
                        .success(true)
                        .build());
                successCount++;
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

    /**
     * The failures {@link VehicleService#createVehicle} raises about the record itself: a VIN
     * that is not 17 valid characters, and a VIN already held by an active vehicle. Both are what
     * {@link com.positivity.vehicle.internal.config.VehicleExceptionHandler} answers as a 4xx on
     * the single-vehicle endpoint, so both describe the caller's own row and are safe to return
     * verbatim. Everything else — a Hibernate error, a malformed stored UUID — is a server-side
     * fault and is reported generically against a correlation id instead (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(VehicleValidationException.class, VehicleVinConflictException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "VEHICLE_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Vehicle ingest failed";
    }
}
