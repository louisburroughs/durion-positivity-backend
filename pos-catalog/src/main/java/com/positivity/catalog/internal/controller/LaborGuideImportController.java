package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.LaborGuideImportSummaryDto;
import com.positivity.catalog.internal.dto.LaborGuideUnmappedOperationDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.LaborGuideIngestService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator surface for labor-guide feed imports (#1569 Phase 1, sourcing plan §5.2/§5.3).
 * Imports are operator-triggered, never lazy read-through refresh — the read path must never
 * pay vendor latency.
 */
@Tag(
        name = "Labor Guide Imports",
        description = "Chunked-manifest ingestion of labor-guide feeds from STORE-licensed sources into"
                + " service labor standards, with counted completeness and an unmapped-operation curation queue.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/labor-guide-imports")
public class LaborGuideImportController {

    private final LaborGuideIngestService ingestService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_IMPORT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_IMPORT})
    @PostMapping
    @EmitEvent(id = "CATALOG_LABOR_GUIDE_IMPORT", apiVersion = "1")
    @Operation(
            operationId = "runLaborGuideImport",
            summary = "Import the Current Labor-Guide Feed Revision",
            description = """
            Opens the named STORE-licensed source's current feed revision and applies every not-yet-applied
            chunk: mapped lines upsert vehicle-keyed labor standards (unchanged lines skip, changed lines
            supersede the active row), and vendor codes with no cross-reference land in the unmapped curation
            queue while the import continues.
            Use this tool after a vendor publishes a new revision, or to resume an import that stopped mid-run;
            do not use it for QUERY_ONLY sources, whose license forbids persisting their times.
            Preconditions: the source must be configured under pos.catalog.labor-guide.providers with STORE mode.
            Required inputs: the sourceCode query parameter; there is no request body.
            Emits a CATALOG_LABOR_GUIDE_IMPORT event; re-running an already-complete revision is a recorded no-op.
            Returns 404 for an unconfigured source and 409 for a QUERY_ONLY source or an unreachable vendor.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The import's counted outcome — COMPLETE only when every chunk and line reconciled.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = LaborGuideImportSummaryDto.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No provider configured for the source",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Source is QUERY_ONLY, or the vendor was unreachable before the import started",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<LaborGuideImportSummaryDto> runImport(
            @Parameter(description = "Configured source to import from, e.g. MOCKGUIDE.", required = true) @RequestParam
                    String sourceCode) {
        return ResponseEntity.ok(ingestService.runImport(sourceCode));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_VIEW})
    @GetMapping("/incomplete")
    @EmitEvent(id = "CATALOG_LABOR_GUIDE_IMPORT_GAPS", apiVersion = "1")
    @Operation(
            operationId = "listIncompleteLaborGuideImports",
            summary = "List Labor-Guide Imports Not Confirmed Complete",
            description = """
            Returns every import whose status is APPLYING or INCOMPLETE — revisions where chunks or lines have
            not reconciled against the vendor's manifest, newest first.
            Use this tool to decide whether to re-run an import (a re-run resumes from the first missing chunk);
            do not use it as an import history, since completed revisions are deliberately not listed.
            Preconditions: none.
            Required inputs: none.
            Emits a CATALOG_LABOR_GUIDE_IMPORT_GAPS event; no state changes.
            Returns 200 with an empty array when every recorded import is complete, and 403 when the caller lacks the view permission.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Imports still applying or closed incomplete.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = LaborGuideImportSummaryDto.class))))
    public ResponseEntity<List<LaborGuideImportSummaryDto>> listIncomplete() {
        return ResponseEntity.ok(ingestService.listIncompleteImports());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_VIEW})
    @GetMapping("/unmapped")
    @EmitEvent(id = "CATALOG_LABOR_GUIDE_UNMAPPED_LIST", apiVersion = "1")
    @Operation(
            operationId = "listUnmappedLaborGuideOperations",
            summary = "List Vendor Operation Codes Awaiting Curation",
            description = """
            Returns vendor operation codes that imports carried but no service_operation_xref row maps, most
            recently seen first, with occurrence counts so a curator can drain the queue by impact.
            Use this tool for mapping curation after an import; do not expect entries to clear themselves —
            adding the xref row is deliberate human work, and the next import then lands the lines.
            Preconditions: none.
            Required inputs: none.
            Emits a CATALOG_LABOR_GUIDE_UNMAPPED_LIST event; no state changes.
            Returns 200 with an empty array when every code the feeds carry is mapped, and 403 when the caller lacks the view permission.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The unmapped-operation curation queue.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array =
                                    @ArraySchema(
                                            schema = @Schema(implementation = LaborGuideUnmappedOperationDto.class))))
    public ResponseEntity<List<LaborGuideUnmappedOperationDto>> listUnmapped() {
        return ResponseEntity.ok(ingestService.listUnmappedOperations());
    }
}
