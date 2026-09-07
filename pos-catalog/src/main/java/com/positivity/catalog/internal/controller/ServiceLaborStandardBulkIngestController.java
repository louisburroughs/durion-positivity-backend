package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.ServiceLaborStandardImportRequestDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServiceLaborStandardService;
import com.positivity.events.EmitEvent;
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

/**
 * Bulk import of vehicle-keyed labor standards (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3).
 *
 * <p>Behind the import permission rather than the manage one, because a row here carries the
 * source and revision that published it and the authoring endpoint deliberately cannot: a shop
 * may state its own number, it may not state one under a vendor's name.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_IMPORT})
@RequestMapping("/v1/catalog/labor-standards")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_IMPORT + "')")
@Tag(name = "Labor Standard Bulk Ingest API", description = "Bulk import vehicle-keyed labor standards")
public class ServiceLaborStandardBulkIngestController
        extends AbstractBulkIngestController<ServiceLaborStandardImportRequestDto> {

    private final ServiceLaborStandardService laborStandardService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_IMPORT + "')")
    @Operation(operationId = "bulkIngestLaborStandards", summary = "Bulk Import Labor Standards", description = """
            Applies a batch of vehicle-keyed labor standards, each addressed by its service's operation \
            code and carrying the source and revision that published it, with a per-row verdict.
            Use this tool to load a labor-time pack from a source that pushes its times; do not use it \
            for a STORE-licensed feed the platform pulls, which arrives through runLaborGuideImport \
            instead.
            Preconditions: every row's operationCode must already name a service, and a row whose \
            vehicle key is already held by an active row of the same source and revision is applied as \
            a no-op.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            operationCode, sourceCode, sourceRevision and laborHours, with the vehicle key, timeType, \
            overlapGroup, includedOpCodes, ownership and publishedAt optional.
            Emits a CATALOG_LABOR_STANDARD_BULK_INGEST event; a row that changes an active standard \
            supersedes it and inserts the replacement, leaving the old row readable for audit.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row the catalog refused carries errorCode LABOR_STANDARD_INGEST_FAILED and the reason; a \
            row lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "CATALOG_LABOR_STANDARD_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of labor standards to apply for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "Wildcard and vehicle-keyed rows",
                                                            value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"operationCode":"FLEET-PM-A-SERVICE","sourceCode":"DURION",
                                                        "sourceRevision":"tier0-fake-2026-09","laborHours":"1.4",
                                                        "timeType":"DURION_STANDARD","ownerScope":"PLATFORM"},
                                                       {"operationCode":"FLEET-PM-A-SERVICE","sourceCode":"DURION",
                                                        "sourceRevision":"tier0-fake-2026-09","laborHours":"2.0",
                                                        "timeType":"DURION_STANDARD","make":"Ford","model":"F-350"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<ServiceLaborStandardImportRequestDto> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(
            @NonNull BulkIngestRequest<ServiceLaborStandardImportRequestDto> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            ServiceLaborStandardImportRequestDto ingestRecord =
                    request.getRecords().get(i);
            try {
                String operationCode = ingestRecord.getOperationCode();
                if (operationCode == null || operationCode.isBlank()) {
                    throw new CatalogValidationException("operationCode is required");
                }
                var applied = laborStandardService.importStandard(operationCode, ingestRecord);
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(applied.getId())
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

    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(
                CatalogValidationException.class, CatalogBusinessRuleException.class, CatalogNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "LABOR_STANDARD_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Labor standard ingest failed";
    }
}
