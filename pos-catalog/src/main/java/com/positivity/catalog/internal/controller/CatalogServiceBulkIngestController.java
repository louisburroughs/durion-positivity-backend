package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.CatalogItemRequestDto;
import com.positivity.catalog.internal.dto.CatalogServiceBulkIngestRecord;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.CatalogService;
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
 * Bulk import of service operations (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3 Tier 2).
 *
 * <p>Exists so that Durion-owned operations stop being a Flyway seed. A {@code R__seed_*.sql}
 * {@code INSERT INTO service} bypasses {@code CatalogFactPublisher} entirely, so
 * {@code catalog.service.updated} never fires and every consumer projecting an
 * {@code ext_catalog_service} replica — pos-workorder's estimate prefill among them — stays cold
 * against precisely the operations the seed added. Loading them through here publishes the fact
 * per row, which is the whole point of the strategy's Tier 2.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_INGEST})
@RequestMapping("/v1/catalog/services")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_INGEST + "')")
@Tag(name = "Catalog Service Bulk Ingest API", description = "Bulk import service operations")
public class CatalogServiceBulkIngestController extends AbstractBulkIngestController<CatalogServiceBulkIngestRecord> {

    private final CatalogService catalogService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_INGEST + "')")
    @Operation(operationId = "bulkIngestCatalogServices", summary = "Bulk Import Service Operations", description = """
            Imports a batch of service operations in one call, creating or updating each by its Durion \
            operation code and publishing a catalog service fact per row, with a per-row success or \
            failure verdict.
            Use this tool to load a pack of operations, such as the Tier 0 tire, Michelin and fleet \
            operations; do not use addCatalogItem, which registers one service at a time and reports \
            its errors as HTTP statuses rather than row results.
            Preconditions: each row's operationCode must be uppercase alphanumeric segments joined by \
            single dashes, and a code already in the catalog updates that service in place instead of \
            failing the row.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            operationCode and name, with shortDescription, longDescription, operationCategory and \
            defaultLaborHours optional.
            Emits a CATALOG_SERVICE_BULK_INGEST event, and each successful row publishes the service \
            fact that hydrates every ext_catalog_service replica.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row the catalog refused carries errorCode CATALOG_SERVICE_INGEST_FAILED and the reason; a \
            row lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "CATALOG_SERVICE_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of service operations to create or update for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Two-operation batch", value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"operationCode":"TPMS-SENSOR-SERVICE","name":"TPMS Service Kit - Set of 4",
                                                        "shortDescription":"Rebuild and reset four TPMS sensors",
                                                        "operationCategory":"TIRE_SERVICE","defaultLaborHours":"0.6"},
                                                       {"operationCode":"LUG-TORQUE-RECHECK","name":"Lug Torque Re-check",
                                                        "operationCategory":"TIRE_SERVICE","defaultLaborHours":"0.3"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<CatalogServiceBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<CatalogServiceBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            try {
                var saved = catalogService.upsertServiceByOperationCode(
                        toCatalogItemRequest(request.getRecords().get(i)));
                results.add(BulkIngestResult.builder()
                        .rowIndex(i)
                        .entityId(saved.getId())
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

    private CatalogItemRequestDto toCatalogItemRequest(@NonNull CatalogServiceBulkIngestRecord ingestRecord) {
        CatalogItemRequestDto dto = new CatalogItemRequestDto();
        dto.setOperationCode(ingestRecord.getOperationCode());
        dto.setName(ingestRecord.getName());
        dto.setShortDescription(ingestRecord.getShortDescription());
        dto.setLongDescription(ingestRecord.getLongDescription());
        dto.setOperationCategory(ingestRecord.getOperationCategory());
        dto.setDefaultLaborHours(IngestValues.decimal(ingestRecord.getDefaultLaborHours(), "defaultLaborHours"));
        return dto;
    }

    /**
     * What the catalog refuses about the record itself — a malformed field, a business rule the
     * service breaks, a reference that does not resolve. All three are what
     * {@link CatalogExceptionHandler} answers as a 4xx on the single-item endpoints, so all three
     * describe the caller's own row. Everything else is a server-side fault, reported generically
     * against a correlation id (issue #1718).
     */
    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(
                CatalogValidationException.class, CatalogBusinessRuleException.class, CatalogNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "CATALOG_SERVICE_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Service ingest failed";
    }
}
