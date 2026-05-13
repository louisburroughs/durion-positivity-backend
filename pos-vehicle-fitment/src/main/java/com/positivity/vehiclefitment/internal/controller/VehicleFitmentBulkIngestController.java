package com.positivity.vehiclefitment.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.vehiclefitment.internal.dto.FitmentBulkIngestRecord;
import com.positivity.vehiclefitment.service.VehicleFitmentService;
import com.positivity.vehiclefitment.service.dto.CreatePartFitmentRequest;
import com.positivity.vehiclefitment.service.dto.PartFitmentResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@PreAuthorize("hasAuthority('vehicle-fitment:hint:create')")
@Tag(name = "Vehicle Fitment Bulk Ingest API", description = "Bulk import vehicle fitment records")
public class VehicleFitmentBulkIngestController extends AbstractBulkIngestController<FitmentBulkIngestRecord> {

    private final VehicleFitmentService vehicleFitmentService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasAuthority('vehicle-fitment:hint:create')")
    @EmitEvent(id = "VEHICLE_FITMENT_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @Valid @RequestBody @NonNull BulkIngestRequest<FitmentBulkIngestRecord> request) {
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
                log.warn("Failed to ingest fitment record at row {}: {}", i, exception.getMessage(), exception);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode("FITMENT_INGEST_FAILED")
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

    private String errorMessage(@NonNull Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Fitment ingest failed" : message;
    }
}
