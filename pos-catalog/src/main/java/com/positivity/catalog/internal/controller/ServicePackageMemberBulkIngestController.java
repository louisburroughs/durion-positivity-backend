package com.positivity.catalog.internal.controller;

import com.positivity.bulkingest.AbstractBulkIngestController;
import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkingest.BulkIngestResult;
import com.positivity.catalog.internal.dto.ServicePackageMemberBulkIngestRecord;
import com.positivity.catalog.internal.dto.ServicePackageMemberRequestDto;
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

/**
 * Bulk import of package membership (#1575 Tier 0; docs/DATA_SEED_STRATEGY.md §3 Tier 2).
 *
 * <p>A separate endpoint from the package ingest because membership is a separate file: a package
 * has to exist before anything can join it, and a member row naming an operation that never loaded
 * should fail only itself rather than take its package's other members with it.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_MANAGE})
@RequestMapping("/v1/service-package-members")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
@Tag(name = "Service Package Member Bulk Ingest API", description = "Bulk import service package membership")
public class ServicePackageMemberBulkIngestController
        extends AbstractBulkIngestController<ServicePackageMemberBulkIngestRecord> {

    private final ServicePackageService servicePackageService;

    @Override
    @PostMapping("/bulk-ingest")
    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
    @Operation(
            operationId = "bulkIngestServicePackageMembers",
            summary = "Bulk Import Service Package Membership",
            description = """
            Applies a batch of package memberships, naming the package by its code and the member by its \
            Durion operation code, with a per-row success or failure verdict.
            Use this tool after bulkIngestServicePackages has loaded the packages themselves; do not use \
            addServicePackageMember, which takes ids rather than codes and refuses an operation the \
            package already carries instead of restating its sequence and quantity.
            Preconditions: both the packageCode and the operationCode must already exist, or that row \
            fails while the rest of the batch proceeds.
            Required inputs: jobId (UUID), locationId (UUID) and a non-empty records array; per record, \
            packageCode and operationCode, with sequence, quantity and required optional.
            Emits a CATALOG_SERVICE_PACKAGE_MEMBER_BULK_INGEST event; a membership that already exists \
            has its sequence, quantity and required flag replaced rather than being duplicated.
            Returns 200 even when rows fail, so callers must inspect successCount, failureCount and the \
            per-row results rather than the HTTP status.
            A row the catalog refused carries errorCode SERVICE_PACKAGE_MEMBER_INGEST_FAILED and the \
            reason; a row lost to a server-side fault carries INTERNAL_ERROR and a correlationId to quote.
            """)
    @ApiResponse(responseCode = "200", description = "Batch processed; per-row verdicts are in the body")
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_MEMBER_BULK_INGEST", apiVersion = "1")
    public ResponseEntity<BulkIngestResponse> bulkIngest(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of package memberships to apply for one ingest job.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @ExampleObject(name = "Two members of one package", value = """
                                                    {"jobId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                     "locationId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c",
                                                     "records":[
                                                       {"packageCode":"TIRE-INSTALL-PKG-4","operationCode":"TIRE-INSTALL-SET-4",
                                                        "sequence":"10","quantity":"1.00","required":"true"},
                                                       {"packageCode":"TIRE-INSTALL-PKG-4","operationCode":"NITROGEN-FILL-SET-4",
                                                        "sequence":"50","quantity":"1.00","required":"false"}]}
                                                    """)))
                    @Valid
                    @RequestBody
                    @NonNull
                    BulkIngestRequest<ServicePackageMemberBulkIngestRecord> request) {
        return super.bulkIngest(request);
    }

    @Override
    protected BulkIngestResponse processRecords(
            @NonNull BulkIngestRequest<ServicePackageMemberBulkIngestRecord> request) {
        List<BulkIngestResult> results = new ArrayList<>();
        int successCount = 0;
        int failureCount = 0;

        for (int i = 0; i < request.getRecords().size(); i++) {
            ServicePackageMemberBulkIngestRecord ingestRecord =
                    request.getRecords().get(i);
            try {
                var saved = servicePackageService.upsertMember(
                        requireCode(ingestRecord.getPackageCode(), "packageCode"),
                        requireCode(ingestRecord.getOperationCode(), "operationCode"),
                        toMemberRequest(ingestRecord));
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

    private ServicePackageMemberRequestDto toMemberRequest(@NonNull ServicePackageMemberBulkIngestRecord ingestRecord) {
        ServicePackageMemberRequestDto dto = new ServicePackageMemberRequestDto();
        dto.setSequence(IngestValues.integer(ingestRecord.getSequence(), "sequence"));
        dto.setQuantity(IngestValues.decimal(ingestRecord.getQuantity(), "quantity"));
        dto.setRequired(IngestValues.bool(ingestRecord.getRequired()));
        return dto;
    }

    private static String requireCode(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new CatalogValidationException(field + " is required");
        }
        return value;
    }

    @Override
    protected Collection<Class<? extends Throwable>> rowRejectionTypes() {
        return List.of(
                CatalogValidationException.class, CatalogBusinessRuleException.class, CatalogNotFoundException.class);
    }

    @Override
    protected String rowRejectionCode() {
        return "SERVICE_PACKAGE_MEMBER_INGEST_FAILED";
    }

    @Override
    protected String rowRejectionFallbackMessage() {
        return "Service package member ingest failed";
    }
}
