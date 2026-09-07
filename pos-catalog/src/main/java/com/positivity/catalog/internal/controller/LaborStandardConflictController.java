package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.LaborStandardConflictDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.LaborStandardConflictService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-source disagreement report for curation (#1569 residual R2, sourcing plan Phase 3
 * item 3). A read-only admin surface, not part of any quote path.
 */
@Tag(
        name = "Labor Standard Conflicts",
        description = "Curation report: pairs of active labor standards from different sources that would"
                + " answer for the same vehicle and time type but disagree.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/labor-standards")
public class LaborStandardConflictController {

    private static final BigDecimal DEFAULT_THRESHOLD = new BigDecimal("0.3");

    private final LaborStandardConflictService conflictService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.LABOR_STANDARD_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.LABOR_STANDARD_VIEW})
    @GetMapping("/conflicts")
    @EmitEvent(id = "CATALOG_LABOR_STANDARD_CONFLICTS", apiVersion = "1")
    @Operation(
            operationId = "listLaborStandardConflicts",
            summary = "List Labor Standards Where Sources Disagree",
            description = """
            Lists pairs of active labor standards from different sources that would both answer for the same \
            vehicle and the same time type, but publish times differing by more than the threshold, widest \
            disagreement first.
            Use this tool to find where a curation decision is owed — which operations two guides price \
            differently, and by how much; do not use it to find the time a job will actually be quoted at, \
            which is resolveLaborTime, because resolution still picks one of these deterministically by \
            precedence.
            Preconditions: none. Rows count as answering for the same vehicle when their keys OVERLAP, not \
            only when they are identical: a manufacturer's wildcard time and an aggregator's time for one \
            year/make/model both answer for that vehicle, which is the disagreement that goes unnoticed \
            today. Time types are never compared across each other — warranty time is meant to differ from \
            retail time.
            Required inputs: none; thresholdHours is optional and defaults to 0.3.
            Emits a CATALOG_LABOR_STANDARD_CONFLICTS event; no state changes.
            Returns 200 with the conflicting pairs, and an empty list when every source agrees within the \
            threshold.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The conflicting pairs, widest disagreement first.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = LaborStandardConflictDto.class))))
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    public ResponseEntity<List<LaborStandardConflictDto>> listLaborStandardConflicts(
            @Parameter(
                            description = "Report a pair only when the two times differ by more than this many"
                                    + " hours. Book time is published in tenths, so anything below 0.1 is noise.",
                            example = "0.3")
                    @RequestParam(required = false)
                    @DecimalMin(value = "0.0", message = "thresholdHours must be 0 or greater")
                    BigDecimal thresholdHours) {
        return ResponseEntity.ok(
                conflictService.findConflicts(thresholdHours == null ? DEFAULT_THRESHOLD : thresholdHours));
    }
}
