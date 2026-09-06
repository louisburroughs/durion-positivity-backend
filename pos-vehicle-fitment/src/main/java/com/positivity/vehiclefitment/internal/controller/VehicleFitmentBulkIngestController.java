package com.positivity.vehiclefitment.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.vehiclefitment.internal.dto.FitmentBulkIngestRecord;
import com.positivity.vehiclefitment.internal.security.VehicleFitmentPermissions;
import com.positivity.vehiclefitment.internal.service.VehicleFitmentService;
import com.positivity.vehiclefitment.internal.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.internal.service.dto.PartFitmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
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
        scopes = {"vehicle-fitment:hint:create"})
@RequestMapping("/v1/fitments")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_CREATE + "')")
@Tag(name = "Vehicle Fitment Bulk Ingest API", description = "Bulk import vehicle fitment records")
public class VehicleFitmentBulkIngestController extends AbstractBulkIngestController<FitmentBulkIngestRecord> {

    private final VehicleFitmentService vehicleFitmentService;

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
             "operatorId":"user-jdoe",
             "records":[{"partNumberId":100245,
                         "manufacturerName":"Toyota Motor Corporation",
                         "makeName":"Toyota",
                         "modelName":"Camry",
                         "vehicleTypeName":"Passenger Car",
                         "vehicleYear":"2018-2022",
                         "engineType":"2.5L I4",
                         "submodel":"SE",
                         "notes":"Verified against OEM catalog"}]}
            """;

    @Override
    @Operation(
            operationId = "bulkIngestVehicleFitments",
            summary = "Bulk Ingest Part Fitment Records",
            description = """
                    Bulk-imports part fitment records, creating one part-to-vehicle fitment row per record and \
                    resolving manufacturer, make, model and vehicle-type names to reference rows.
                    Use this tool for catalog-scale fitment loads from a prepared batch; do not use \
                    createVehicleHint, which manages per-product applicability hint tags rather than part fitment \
                    rows.
                    Preconditions: the caller must hold vehicle-fitment:hint:create; referenced manufacturer, make, \
                    model and vehicle-type names need not pre-exist, because each is matched case-insensitively and \
                    created on the fly when missing.
                    Required inputs: jobId (UUID), locationId (UUID) and records, a non-empty list where each record \
                    requires partNumberId (numeric); manufacturerName, makeName, modelName, vehicleTypeName, \
                    vehicleYear, engineType, submodel and notes are optional, and jobId, locationId and operatorId \
                    are accepted for the envelope but not persisted by this module.
                    Emits a VEHICLE_FITMENT_BULK_INGEST event covering the whole batch; rows are processed \
                    independently, so one failed row does not roll back the others.
                    Returns 200 even when every row fails, so callers must inspect each result's success flag and \
                    errorCode rather than trusting the status alone. Fitment creation resolves or creates every name \
                    it is given and refuses nothing about a row, so a failure here is a server-side fault: the row \
                    carries INGEST_INTERNAL_ERROR and an errorMessage holding only a correlationId to quote.
                    """)
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('" + VehicleFitmentPermissions.HINT_CREATE + "')")
    @EmitEvent(id = "VEHICLE_FITMENT_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "Batch envelope of part fitment records to import, each naming the vehicle the part applies to.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Single fitment record",
                                                            value = BULK_INGEST_EXAMPLE)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<FitmentBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<FitmentBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            FitmentBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                CreatePartFitmentRequest createRequest = new CreatePartFitmentRequest(ingestRecord.getPartNumberId());
                createRequest.setManufacturerName(ingestRecord.getManufacturerName());
                createRequest.setMakeName(ingestRecord.getMakeName());
                createRequest.setModelName(ingestRecord.getModelName());
                createRequest.setVehicleTypeName(ingestRecord.getVehicleTypeName());
                createRequest.setVehicleYear(ingestRecord.getVehicleYear());
                createRequest.setEngineType(ingestRecord.getEngineType());
                createRequest.setSubmodel(ingestRecord.getSubmodel());
                createRequest.setNotes(ingestRecord.getNotes());

                PartFitmentResponse created = vehicleFitmentService.createFitment(createRequest);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
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
     * No rejection types: {@link VehicleFitmentService#createFitment} refuses nothing about the
     * record. Every name it is given is resolved or created, so a failure on this path is a
     * persistence or upstream fault, not the caller's row — and
     * {@code VehicleFitmentException}, the module's only exception type, wraps exactly those
     * (it is raised on unparseable upstream responses and on an insert race, and no advice in
     * this module maps it to a 4xx). So every failure here reports generically against a
     * correlation id (issue #1718), which is what those failures are.
     */
    @Override
    protected String rowRejectionCode() {
        return "FITMENT_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Fitment ingest failed";
    }
}
