package com.positivity.location.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.LocationBulkIngestRecord;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationTypeDTO;
import com.positivity.location.service.LocationService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/locations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Location Bulk Ingest API", description = "Bulk import locations")
public class LocationBulkIngestController extends AbstractBulkIngestController<LocationBulkIngestRecord> {

    private static final String DEFAULT_LOCATION_TYPE_NAME = "STORE";
    private final LocationService locationService;

    @Override
    @PreAuthorize("hasAuthority('location:write')")
    @EmitEvent(id = "LOCATION_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @Valid @RequestBody @NonNull BulkIngestRequest<LocationBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<LocationBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            LocationBulkIngestRecord record = request.getRecords().get(i);
            try {
                var created = locationService.createLocation(toLocationRequest(record));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest location record at row {}: {}", i, exception.getMessage());
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .success(false)
                        .errorCode("LOCATION_INGEST_FAILED")
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

    private LocationRequestDTO toLocationRequest(@NonNull LocationBulkIngestRecord record) {
        LocationRequestDTO request = new LocationRequestDTO();
        request.setName(record.getName());
        request.setCode(record.getCode());
        request.setAddressLine1(record.getAddressLine1());
        request.setAddressLine2(record.getAddressLine2());
        request.setCity(record.getCity());
        request.setState(record.getStateOrProvince());
        request.setPostalCode(record.getPostalCode());
        request.setCountry(record.getCountryCode());
        request.setActive(record.getActive() == null ? Boolean.TRUE : record.getActive());
        request.setType(LocationTypeDTO.builder()
                .name(firstNonBlank(record.getLocationTypeName(), DEFAULT_LOCATION_TYPE_NAME))
                .build());
        return request;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private String errorMessage(@NonNull Exception exception) {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "Location ingest failed" : message;
    }
}
