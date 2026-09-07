package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.ServicePackageBulkIngestRecord;
import com.positivity.catalog.internal.dto.ServicePackageRequestDto;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServicePackageService;
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

/** Bulk import of service packages (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3 Tier 2). */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_MANAGE})
@RequestMapping("/v1/service-packages")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
@Tag(name = "Service Package Bulk Ingest API", description = "Bulk import service packages")
public class ServicePackageBulkIngestController extends AbstractBulkIngestController<ServicePackageBulkIngestRecord> {

    private final ServicePackageService servicePackageService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
    @Operation(operationId = "bulkIngestServicePackages", summary = "Bulk Import Service Packages", description = """
            Imports a batch of service packages in one call, creating or updating each by its package \
            code, with a per-row success or failure verdict.
            Use this tool to load a pack of packages and fleet requirement sets; do not use \
            createServicePackage, which authors one package at a time and refuses a code that already \
            exists rather than updating it.
            Preconditions: each row's packageCode must be uppercase alphanumeric segments joined by \
            single dashes, and a SHOP-scoped row must name its owning location while a PLATFORM one must \
            not.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            packageCode, name and packageLaborHours, with description, ownership, fleetPartyId, active \
            and the effective window optional.
            Emits a CATALOG_SERVICE_PACKAGE_BULK_INGEST event; membership is untouched here, so a \
            package's members survive a re-load and arrive through bulkIngestServicePackageMembers.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row the catalog refused carries errorCode SERVICE_PACKAGE_INGEST_FAILED and the reason; a \
            row lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of service packages to create or update for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(
                                                            name = "An offering and a requirement set",
                                                            value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"packageCode":"TIRE-INSTALL-PKG-4","name":"Four Tire Installation Package",
                                                        "packageLaborHours":"1.6","ownerScope":"PLATFORM","active":"true"},
                                                       {"packageCode":"FLEET-REQ-TARHEEL","name":"Tarheel Logistics - Standing Requirements",
                                                        "packageLaborHours":"2.3","ownerScope":"PLATFORM",
                                                        "fleetPartyId":"0198f2a1-0000-7000-8000-0000000000f1"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<ServicePackageBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(@NonNull BulkIngestRequest<ServicePackageBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            try {
                var saved = servicePackageService.upsert(
                        toPackageRequest(request.getRecords().get(i)));
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

    private ServicePackageRequestDto toPackageRequest(@NonNull ServicePackageBulkIngestRecord ingestRecord) {
        ServicePackageRequestDto dto = new ServicePackageRequestDto();
        dto.setPackageCode(ingestRecord.getPackageCode());
        dto.setName(ingestRecord.getName());
        dto.setDescription(ingestRecord.getDescription());
        dto.setOwnerScope(ingestRecord.getOwnerScope());
        dto.setOwnerLocationId(IngestValues.uuid(ingestRecord.getOwnerLocationId(), "ownerLocationId"));
        dto.setFleetPartyId(IngestValues.uuid(ingestRecord.getFleetPartyId(), "fleetPartyId"));
        dto.setPackageLaborHours(IngestValues.decimal(ingestRecord.getPackageLaborHours(), "packageLaborHours"));
        dto.setActive(IngestValues.bool(ingestRecord.getActive()));
        dto.setEffectiveFrom(IngestValues.date(ingestRecord.getEffectiveFrom(), "effectiveFrom"));
        dto.setEffectiveTo(IngestValues.date(ingestRecord.getEffectiveTo(), "effectiveTo"));
        return dto;
    }

    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(
                CatalogValidationException.class, CatalogBusinessRuleException.class, CatalogNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "SERVICE_PACKAGE_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Service package ingest failed";
    }
}
