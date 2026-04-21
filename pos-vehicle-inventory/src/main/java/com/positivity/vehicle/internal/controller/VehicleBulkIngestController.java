package com.positivity.vehicle.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.shared.dto.CreateVehicleRequest;
import com.positivity.vehicle.internal.dto.VehicleBulkIngestRecord;
import com.positivity.vehicle.service.VehicleService;
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
@RequestMapping("/v1/vehicles")
@RequiredArgsConstructor
@Slf4j
@PreAuthorize("hasAuthority('vehicle-inventory:registry:create')")
@Tag(name = "Vehicle Bulk Ingest API", description = "Bulk import vehicle records")
public class VehicleBulkIngestController extends AbstractBulkIngestController<VehicleBulkIngestRecord> {

  private final VehicleService vehicleService;

  @Override
  @PostMapping("/bulk-ingest")
  @PreAuthorize("hasAuthority('vehicle-inventory:registry:create')")
  @EmitEvent(id = "VEHICLE_BULK_INGEST", apiVersion = "1")
  public ResponseEntity<BulkIngestResponse> bulkIngest(
      @Valid @RequestBody @NonNull BulkIngestRequest<VehicleBulkIngestRecord> request) {
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
        log.warn("Failed to ingest vehicle record at row {}: {}", i, exception.getMessage(), exception);
        results.add(BulkIngestResult.builder()
            .rowIndex(i)
            .success(false)
            .errorCode("VEHICLE_INGEST_FAILED")
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
    return message == null || message.isBlank() ? "Vehicle ingest failed" : message;
  }
}
