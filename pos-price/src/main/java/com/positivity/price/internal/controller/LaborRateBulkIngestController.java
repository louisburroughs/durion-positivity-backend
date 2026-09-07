package com.positivity.price.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.LaborRateBulkIngestRecord;
import com.positivity.price.internal.dto.LaborRateRequest;
import com.positivity.price.internal.exception.LaborRateValidationException;
import com.positivity.price.internal.security.PricingPermissions;
import com.positivity.price.internal.service.LaborRateAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

/** Bulk import of hourly labor rates (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3 Tier 2). */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", PricingPermissions.LABOR_RATE_MANAGE})
@RequestMapping("/v1/labor-rates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
@Tag(name = "Labor Rate Bulk Ingest API", description = "Bulk import hourly labor rates")
public class LaborRateBulkIngestController extends AbstractBulkIngestController<LaborRateBulkIngestRecord> {

    private final LaborRateAdminService laborRateAdminService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
    @Operation(operationId = "bulkIngestLaborRates", summary = "Bulk Import Labor Rates", description = """
            Imports a batch of hourly labor rates in one call, storing each whose scope and start \
            instant are not already taken, with a per-row success or failure verdict.
            Use this tool to load a rate card across several locations and categories; do not use \
            createLaborRate, which stores one rate at a time and reports its errors as HTTP statuses \
            rather than row results.
            Preconditions: a row whose location, category and effectiveFrom are already held is \
            answered with the stored row unchanged, because a rate that has priced an invoice is never \
            rewritten.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            currency, hourlyRate and effectiveFrom, with the rate's own locationId, operationCategory \
            and effectiveTo optional.
            Emits a PRICE_LABOR_RATE_BULK_INGEST event; a changed rate is a new row in a new window \
            rather than an edit of the old one.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row pricing refused carries errorCode LABOR_RATE_INGEST_FAILED and the reason; a row lost \
            to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "PRICE_LABOR_RATE_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of labor rates to store for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "A platform default and a shop rate",
                                                            value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"currency":"USD","hourlyRate":"125.0000",
                                                        "effectiveFrom":"2026-01-01T00:00:00Z"},
                                                       {"locationId":"0198f2a1-0000-7000-8000-00000000000a",
                                                        "operationCategory":"TIRE_SERVICE","currency":"USD",
                                                        "hourlyRate":"105.0000","effectiveFrom":"2026-01-01T00:00:00Z"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<LaborRateBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<LaborRateBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            try {
                var stored = laborRateAdminService.upsertRate(
                        toRateRequest(request.getRecords().get(i)));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(stored.getId())
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

    private LaborRateRequest toRateRequest(@NonNull LaborRateBulkIngestRecord ingestRecord) {
        LaborRateRequest dto = new LaborRateRequest();
        dto.setLocationId(PriceIngestValues.uuid(ingestRecord.getLocationId(), "locationId"));
        dto.setOperationCategory(ingestRecord.getOperationCategory());
        dto.setCurrency(ingestRecord.getCurrency());
        dto.setHourlyRate(PriceIngestValues.decimal(ingestRecord.getHourlyRate(), "hourlyRate"));
        dto.setEffectiveFrom(PriceIngestValues.instant(ingestRecord.getEffectiveFrom(), "effectiveFrom"));
        dto.setEffectiveTo(PriceIngestValues.instant(ingestRecord.getEffectiveTo(), "effectiveTo"));
        return dto;
    }

    /**
     * What pricing refuses about the record itself: an unknown category, a non-positive rate, an
     * inverted window. That is what {@code GlobalExceptionHandler} answers as a 4xx on the
     * single-rate endpoint, so it is exactly what describes the caller's own row. Everything else
     * is a server-side fault, reported generically against a correlation id (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(LaborRateValidationException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "LABOR_RATE_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Labor rate ingest failed";
    }
}
