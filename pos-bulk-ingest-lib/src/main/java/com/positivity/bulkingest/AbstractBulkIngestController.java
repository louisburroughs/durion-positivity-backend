package com.positivity.bulkingest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/**
 * Abstract base controller for bulk-ingest endpoints.
 * Concrete subclasses extend this and add @RequestMapping for their resource path.
 * The POST /bulk-ingest endpoint is provided here.
 *
 * @param <T> the record type for this domain's bulk ingest
 */
public abstract class AbstractBulkIngestController<T> {

    @Operation(
            summary = "Bulk ingest records",
            description = "Accepts a batch of domain records for bulk import. Returns per-record results.")
    @ApiResponse(responseCode = "200", description = "Batch processed (check per-record success/failure in response)")
    @ApiResponse(responseCode = "400", description = "Invalid request payload")
    @PostMapping("/bulk-ingest")
    public ResponseEntity<BulkIngestResponse> bulkIngest(@Valid @RequestBody @NonNull BulkIngestRequest<T> request) {
        BulkIngestResponse response = processRecords(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Implement this in each target service's concrete controller.
     * Validate, transform, and persist the records.
     * Return a BulkIngestResponse with per-record results.
     */
    protected abstract BulkIngestResponse processRecords(@NonNull BulkIngestRequest<T> request);
}
