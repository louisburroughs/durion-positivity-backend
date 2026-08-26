package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.SupplierArticleCodeReplayService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Operator tool to re-emit vendor-article-code facts for event-fed replica consumers (CAP-320
 * #1347, following #1309's product-fact replay).
 */
@Tag(
        name = "Supplier Article Codes",
        description = "Replay of vendor-specific article-code facts derived from PRICAT imports, for seeding or"
                + " repairing consumer replicas such as pos-order's purchase-order transmission.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/supplier-article-codes")
public class SupplierArticleCodeController {

    private final SupplierArticleCodeReplayService supplierArticleCodeReplayService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.FACT_REPLAY + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.FACT_REPLAY})
    @PostMapping("/replay")
    @EmitEvent(id = "CATALOG_SUPPLIER_ARTICLE_CODE_REPLAY", apiVersion = "1")
    @Operation(
            operationId = "replaySupplierArticleCodeFacts",
            summary = "Re-emit Vendor Article Code Facts for Replica Consumers",
            description = """
            Re-publishes catalog.supplier-article-code.updated facts for one bounded page of vendor-article-code \
            rows so that event-fed replicas in other modules can be seeded or repaired, returning what it \
            emitted and a cursor for the next page.
            Use this tool to fill a consumer's replica after a first deployment or a consumer outage longer than \
            broker retention; do not use it to fix one row, which republishes itself on its next PRICAT import.
            Preconditions: Kafka publication must be enabled, or the facts queue in the outbox and reach nobody; \
            replayed facts are indistinguishable from live ones, so consumers apply them through their normal \
            path and their stale guard prevents an older fact regressing newer state.
            Required inputs: none; afterId resumes a previous page, updatedSince restricts to rows changed at or \
            after an instant, and limit bounds the page at 1000.
            Emits a CATALOG_SUPPLIER_ARTICLE_CODE_REPLAY event and queues one fact per row in the page; no \
            catalog state changes.
            Returns 200 with complete=true and a null cursor once the table end is reached, and 400 when limit \
            is out of range or a parameter is malformed.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "What this page emitted and where to resume.",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SupplierArticleCodeReplayResultDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "A parameter is malformed or the limit is out of range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<SupplierArticleCodeReplayResultDto> replaySupplierArticleCodeFacts(
            @Parameter(
                            description = "Resume cursor from a previous call; omit to start at the beginning.",
                            schema =
                                    @Schema(
                                            type = "string",
                                            format = "uuid",
                                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"))
                    @RequestParam(required = false)
                    UUID afterId,
            @Parameter(
                            description = "Restrict to rows changed at or after this instant; omit to replay all.",
                            schema = @Schema(type = "string", format = "date-time", example = "2026-08-01T00:00:00Z"))
                    @RequestParam(required = false)
                    Instant updatedSince,
            @Parameter(
                            description = "Maximum facts to emit in this call, 1-1000.",
                            schema = @Schema(type = "integer", example = "500", defaultValue = "500"))
                    @RequestParam(defaultValue = "500")
                    @Min(1)
                    @Max(1000)
                    int limit) {
        return ResponseEntity.ok(supplierArticleCodeReplayService.replayPage(afterId, updatedSince, limit));
    }
}
