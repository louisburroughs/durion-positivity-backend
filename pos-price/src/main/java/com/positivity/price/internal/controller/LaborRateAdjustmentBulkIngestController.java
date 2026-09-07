package com.positivity.price.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.events.EmitEvent;
import com.positivity.price.internal.dto.LaborRateAdjustmentBulkIngestRecord;
import com.positivity.price.internal.dto.LaborRateAdjustmentRequest;
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

/** Bulk import of labor-matrix adjustment steps (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3). */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", PricingPermissions.LABOR_RATE_MANAGE})
@RequestMapping("/v1/labor-rate-adjustments")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
@Tag(name = "Labor Rate Adjustment Bulk Ingest API", description = "Bulk import labor-matrix adjustment steps")
public class LaborRateAdjustmentBulkIngestController
        extends AbstractBulkIngestController<LaborRateAdjustmentBulkIngestRecord> {

    private final LaborRateAdminService laborRateAdminService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + PricingPermissions.LABOR_RATE_MANAGE + "')")
    @Operation(
            operationId = "bulkIngestLaborRateAdjustments",
            summary = "Bulk Import Labor Matrix Adjustments",
            description = """
            Imports a batch of labor-matrix adjustment steps in one call, storing each whose scope, code \
            and start instant are not already taken, with a per-row verdict.
            Use this tool to load a whole matrix at once, where the steps' sequences relative to each \
            other are the point; do not use createLaborRateAdjustment, which stores one step at a time \
            and cannot show that ordering.
            Preconditions: a row whose location, category, adjustmentCode and effectiveFrom are already \
            held is answered with the stored step unchanged, because a step that has priced an invoice \
            is never rewritten.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            adjustmentCode, adjustmentType, adjustmentValue, sequence and effectiveFrom, with the step's \
            own locationId, operationCategory, description and effectiveTo optional.
            Emits a PRICE_LABOR_RATE_ADJUSTMENT_BULK_INGEST event; percentage steps compound in sequence \
            order, so a batch that renumbers them changes what the matrix charges.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row pricing refused carries errorCode LABOR_RATE_ADJUSTMENT_INGEST_FAILED and the reason; a \
            row lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "PRICE_LABOR_RATE_ADJUSTMENT_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of labor-matrix adjustment steps to store for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "A surcharge and a contract discount",
                                                            value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"adjustmentCode":"CORROSION","adjustmentType":"PERCENT",
                                                        "adjustmentValue":"15.0000","sequence":"10",
                                                        "effectiveFrom":"2026-01-01T00:00:00Z"},
                                                       {"adjustmentCode":"FLEET_CONTRACT","adjustmentType":"PERCENT",
                                                        "adjustmentValue":"-10.0000","sequence":"90",
                                                        "effectiveFrom":"2026-01-01T00:00:00Z"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<LaborRateAdjustmentBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(
            @NonNull BulkIngestRequest<LaborRateAdjustmentBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            try {
                var stored = laborRateAdminService.upsertAdjustment(
                        toAdjustmentRequest(request.getRecords().get(i)));
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

    private LaborRateAdjustmentRequest toAdjustmentRequest(@NonNull LaborRateAdjustmentBulkIngestRecord ingestRecord) {
        LaborRateAdjustmentRequest dto = new LaborRateAdjustmentRequest();
        dto.setLocationId(PriceIngestValues.uuid(ingestRecord.getLocationId(), "locationId"));
        dto.setOperationCategory(ingestRecord.getOperationCategory());
        dto.setAdjustmentCode(ingestRecord.getAdjustmentCode());
        dto.setDescription(ingestRecord.getDescription());
        dto.setAdjustmentType(ingestRecord.getAdjustmentType());
        dto.setAdjustmentValue(PriceIngestValues.decimal(ingestRecord.getAdjustmentValue(), "adjustmentValue"));
        dto.setSequence(PriceIngestValues.integer(ingestRecord.getSequence(), "sequence"));
        dto.setEffectiveFrom(PriceIngestValues.instant(ingestRecord.getEffectiveFrom(), "effectiveFrom"));
        dto.setEffectiveTo(PriceIngestValues.instant(ingestRecord.getEffectiveTo(), "effectiveTo"));
        return dto;
    }

    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(LaborRateValidationException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "LABOR_RATE_ADJUSTMENT_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Labor rate adjustment ingest failed";
    }
}
