package com.positivity.bulkingest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private final Logger log = LoggerFactory.getLogger(getClass());

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

    /**
     * Reports a row that threw, saying only what the caller is entitled to know (issue #1718).
     *
     * <p>A row whose exception is named by {@link #rowRejectionTypes()} is reported with
     * {@link #rowRejectionCode()} and the exception's own message, because the owning service
     * refused the submitted record and the message is how the caller fixes it. Anything else is a
     * server-side fault: it is logged here at ERROR against a correlation id, and the caller gets
     * {@link BulkIngestFailures#INTERNAL_ERROR_CODE} and that id — never the exception's text,
     * which can name internal classes, columns and query fragments.
     *
     * <p>Call this from the per-record {@code catch} in {@link #processRecords}; do not build a
     * failure result by hand, because that is the shape the disclosure defect took.
     */
    protected final BulkIngestResult rowFailure(int rowIndex, @NonNull Exception exception) {
        if (BulkIngestFailures.isRowRejection(exception, rowRejectionTypes())) {
            log.warn("Rejected record at row {}: {}", rowIndex, exception.getMessage());
            return BulkIngestFailures.rejected(rowIndex, rowRejectionCode(), exception, rowRejectionFallbackMessage());
        }

        String correlationId = BulkIngestFailures.correlationId();
        log.error("Failed to ingest record at row {} [correlationId={}]", rowIndex, correlationId, exception);
        return BulkIngestFailures.internalError(rowIndex, correlationId);
    }

    /**
     * The exception types that mean "the owning service refused this record", whose messages are
     * therefore safe and useful to return. In practice these are exactly the module's own domain
     * exceptions that its {@code @RestControllerAdvice} maps to a 4xx.
     *
     * <p>Defaults to none, so a module that has not classified its failures reports every one of
     * them generically. That is deliberate: an incomplete list costs a caller some detail, while
     * an over-broad one leaks server internals.
     */
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of();
    }

    /** Machine-readable reason code for a rejected row. */
    protected String rowRejectionCode() {
        return "INGEST_RECORD_REJECTED";
    }

    /** Reported for a rejected row whose exception carries no message of its own. */
    protected String rowRejectionFallbackMessage() {
        return "Record rejected";
    }
}
