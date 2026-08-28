package com.positivity.location.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.location.internal.dto.LocationBulkIngestRecord;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationTypeDTO;
import com.positivity.location.internal.security.LocationPermissions;
import com.positivity.location.internal.service.LocationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
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
@SecurityRequirement(
        name = "bearerAuth",
        scopes = {"location:write"})
@RequestMapping("/v1/locations")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Location Bulk Ingest API", description = "Bulk import locations")
public class LocationBulkIngestController extends AbstractBulkIngestController<LocationBulkIngestRecord> {

    private static final String DEFAULT_LOCATION_TYPE_NAME = "STORE";

    private static final String BULK_INGEST_EXAMPLE = """
            {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01",
             "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02",
             "operatorId":"user-jdoe",
             "records":[{"name":"Downtown Service Center",
                         "code":"LOC-001",
                         "addressLine1":"123 Main St",
                         "city":"Springfield",
                         "stateOrProvince":"IL",
                         "postalCode":"62704",
                         "countryCode":"US",
                         "phoneNumber":"+1-217-555-0100",
                         "timezone":"America/Chicago",
                         "active":true,
                         "locationTypeName":"STORE"}]}
            """;

    private final LocationService locationService;

    @Override
    @Operation(operationId = "bulkIngestLocations", summary = "Bulk Import a Batch of Locations", description = """
                    Imports a batch of location records in one call, creating each row independently and \
                    reporting per-record success or failure.
                    Use this tool for initial data loads or migrations; do not use createLocation, which creates \
                    a single location per call.
                    Preconditions: names and codes must be unique against existing locations; each record is \
                    validated independently, so one failure does not abort the batch.
                    Required inputs: jobId, locationId and a non-empty records array, each record with name and \
                    code; active defaults to true and locationTypeName defaults to STORE when omitted.
                    Emits a LOCATION_BULK_INGEST event, and every successfully created location also publishes a \
                    location fact for replica consumers.
                    Returns 200 with per-record results even when some records fail (failed rows carry errorCode \
                    LOCATION_INGEST_FAILED), and 400 when the batch envelope itself is invalid.
                    """)
    @ApiResponse(responseCode = "200", description = "Batch processed (check per-record success/failure in response)")
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @PreAuthorize("hasAuthority('" + LocationPermissions.WRITE + "')")
    @EmitEvent(id = "LOCATION_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch envelope of location records to import, scoped to a bulk ingest"
                                    + " job and submitting operator.",
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
                    BulkIngestRequest<LocationBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<LocationBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            LocationBulkIngestRecord ingestRecord = request.getRecords().get(i);
            try {
                var created = locationService.createLocation(toLocationRequest(ingestRecord));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(created.getId())
                        .success(true)
                        .build());
                successCount++;
            } catch (Exception exception) {
                log.warn("Failed to ingest location record at row {}: {}", i, exception.getMessage(), exception);
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

    private LocationRequestDTO toLocationRequest(@NonNull LocationBulkIngestRecord ingestRecord) {
        LocationRequestDTO request = new LocationRequestDTO();
        request.setName(ingestRecord.getName());
        request.setCode(ingestRecord.getCode());
        request.setAddressLine1(ingestRecord.getAddressLine1());
        request.setAddressLine2(ingestRecord.getAddressLine2());
        request.setCity(ingestRecord.getCity());
        request.setState(ingestRecord.getStateOrProvince());
        request.setPostalCode(ingestRecord.getPostalCode());
        request.setCountry(ingestRecord.getCountryCode());
        request.setPhoneNumber(ingestRecord.getPhoneNumber());
        request.setTimezone(ingestRecord.getTimezone());
        request.setActive(ingestRecord.getActive() == null ? Boolean.TRUE : ingestRecord.getActive());
        request.setType(LocationTypeDTO.builder()
                .name(firstNonBlank(ingestRecord.getLocationTypeName(), DEFAULT_LOCATION_TYPE_NAME))
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
