package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.scrap.ApproveScrapRequest;
import com.positivity.inventory.internal.dto.scrap.CreateScrapRequest;
import com.positivity.inventory.internal.dto.scrap.RejectScrapRequest;
import com.positivity.inventory.internal.dto.scrap.ScrapResponse;
import com.positivity.inventory.internal.enums.ScrapReasonCode;
import com.positivity.inventory.internal.enums.ScrapStatus;
import com.positivity.inventory.service.ScrapService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for the scrap/write-off workflow (odoo-parity D1, issue #1030).
 *
 * <p>Scraps are evaluated against value-based approval thresholds on creation; below-threshold
 * scraps auto-post {@code SCRAP_OUT} in the create transaction, above-threshold (or
 * unknown-value) scraps require approval before posting.
 */
@RestController
@RequestMapping("/v1/inventory/scraps")
@RequiredArgsConstructor
@Slf4j
@Tag(name = "Scraps", description = "Scrap (write-off) documents with value-threshold approval and SCRAP_OUT posting")
public class ScrapController {

    private final ScrapService scrapService;

    /**
     * Creates a scrap document; below-threshold scraps auto-approve and post immediately.
     *
     * @param request the scrap creation request
     * @return the created scrap with its resulting status
     */
    @PostMapping
    @EmitEvent(id = "INVENTORY_SCRAP_CREATE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:create"})
    @PreAuthorize("hasAuthority('inventory:scrap:create')")
    @Operation(
            summary = "Create scrap document",
            description = "Creates a scrap (write-off) document. Below the configured value thresholds the scrap"
                    + " is auto-approved and posted as SCRAP_OUT in the same transaction; above them (or when"
                    + " no cost is derivable) it enters PENDING_APPROVAL.",
            tags = {"Scraps"})
    @ApiResponse(responseCode = "201", description = "Scrap created (AUTO_APPROVED/POSTED or PENDING_APPROVAL)")
    @ApiResponse(
            responseCode = "400",
            description = "Invalid request (e.g. OTHER reason without notes)",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Missing inventory:scrap:create, or override requested without inventory:adjustment:override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient on-hand for the auto-approved posting (SCRAP_INSUFFICIENT_STOCK):"
                    + " reconcile via cycle count or adjustment, or use an authorized negative-stock override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> createScrap(@Valid @RequestBody CreateScrapRequest request) {
        log.info("Received request to scrap {} of {}", request.getQuantity(), request.getStockItemId());
        return ResponseEntity.status(HttpStatus.CREATED).body(scrapService.createScrap(request));
    }

    /**
     * Approves a pending scrap and posts it to the ledger.
     *
     * @param scrapId the scrap document id
     * @param request the approval request (optional negative-stock override)
     * @return the approved and posted scrap
     */
    @PostMapping("/{scrapId}/approve")
    @EmitEvent(id = "INVENTORY_SCRAP_APPROVE", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:approve"})
    @PreAuthorize("hasAuthority('inventory:scrap:approve')")
    @Operation(
            summary = "Approve scrap",
            description = "Approves a pending scrap and posts its SCRAP_OUT entry to the inventory ledger",
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap approved and posted")
    @ApiResponse(
            responseCode = "403",
            description =
                    "Missing inventory:scrap:approve, or override requested without inventory:adjustment:override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Scrap is not in an approvable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Insufficient on-hand (SCRAP_INSUFFICIENT_STOCK): reconcile via cycle count or"
                    + " adjustment, or use an authorized negative-stock override",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> approveScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId,
            @Valid @RequestBody ApproveScrapRequest request) {
        log.info("Received request to approve scrap {}", scrapId);
        return ResponseEntity.ok(scrapService.approveScrap(scrapId, request));
    }

    /**
     * Rejects a pending scrap; no inventory changes are made.
     *
     * @param scrapId the scrap document id
     * @param request the rejection request with reason
     * @return the rejected scrap
     */
    @PostMapping("/{scrapId}/reject")
    @EmitEvent(id = "INVENTORY_SCRAP_REJECT", apiVersion = "1")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:approve"})
    @PreAuthorize("hasAuthority('inventory:scrap:approve')")
    @Operation(
            summary = "Reject scrap",
            description = "Rejects a pending scrap with a reason. No inventory changes are made.",
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap rejected")
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Scrap is not in a rejectable state",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> rejectScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId,
            @Valid @RequestBody RejectScrapRequest request) {
        log.info("Received request to reject scrap {}", scrapId);
        return ResponseEntity.ok(scrapService.rejectScrap(scrapId, request));
    }

    /**
     * Retrieves one scrap document.
     *
     * @param scrapId the scrap document id
     * @return the scrap
     */
    @GetMapping("/{scrapId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:view", "inventory:scrap:approve"})
    @PreAuthorize("hasAnyAuthority('inventory:scrap:view','inventory:scrap:approve')")
    @Operation(
            summary = "Get scrap details",
            description = "Retrieves one scrap document",
            tags = {"Scraps"})
    @ApiResponse(responseCode = "200", description = "Scrap found")
    @ApiResponse(
            responseCode = "404",
            description = "Scrap not found",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ScrapResponse> getScrap(
            @Parameter(description = "Scrap document ID", required = true) @PathVariable UUID scrapId) {
        return ResponseEntity.ok(scrapService.getScrap(scrapId));
    }

    /**
     * Lists scrap documents matching the optional filters, newest first.
     *
     * @param reasonCode optional reason filter
     * @param status optional status filter
     * @param locationId optional location filter
     * @param createdFrom optional inclusive lower creation-time bound (ISO-8601 instant)
     * @param createdTo optional inclusive upper creation-time bound (ISO-8601 instant)
     * @return matching scraps
     */
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:scrap:view", "inventory:scrap:approve"})
    @PreAuthorize("hasAnyAuthority('inventory:scrap:view','inventory:scrap:approve')")
    @Operation(
            summary = "List scraps",
            description = "Lists scrap documents filtered by reason, status, location, and creation-date range",
            tags = {"Scraps"})
    @ApiResponse(
            responseCode = "200",
            description = "Scraps retrieved",
            content =
                    @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            array = @ArraySchema(schema = @Schema(implementation = ScrapResponse.class))))
    public ResponseEntity<List<ScrapResponse>> listScraps(
            @Parameter(description = "Filter by scrap reason") @RequestParam(required = false)
                    ScrapReasonCode reasonCode,
            @Parameter(description = "Filter by lifecycle status") @RequestParam(required = false) ScrapStatus status,
            @Parameter(description = "Filter by location") @RequestParam(required = false) UUID locationId,
            @Parameter(description = "Inclusive lower creation-time bound")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant createdFrom,
            @Parameter(description = "Inclusive upper creation-time bound")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant createdTo) {
        return ResponseEntity.ok(scrapService.listScraps(reasonCode, status, locationId, createdFrom, createdTo));
    }
}
