package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.AddSuppressionRequest;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.dto.SuppressionEntryResponse;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.SuppressionService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hard address-level marketing suppression (Story #1140).
 *
 * <p>Entries here outrank consent and are consulted by every send. Raw addresses are never
 * stored or returned — only a normalized hash for lookup and a masked hint for review.
 */
@Tag(name = "CRM Suppression", description = "Hard address-level marketing suppression list")
@RestController
@RequestMapping("/v1/crm/suppression")
public class CrmSuppressionController {

    private final SuppressionService suppressionService;

    public CrmSuppressionController(SuppressionService suppressionService) {
        this.suppressionService = suppressionService;
    }

    @Operation(summary = "List suppression entries", description = "List suppressed addresses, newest first")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Entries returned",
                content = @Content(schema = @Schema(implementation = PagedResponse.class))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_VIEW})
    @PreAuthorize("hasAuthority('crm:suppression:view')")
    @EmitEvent(id = "CRM_SUPPRESSION_LIST", apiVersion = "1")
    public ResponseEntity<PagedResponse<SuppressionEntryResponse>> list(
            @RequestParam(name = "channel", required = false) MarketingChannel channel,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok(suppressionService.list(channel, page, size));
    }

    @Operation(
            summary = "Check address suppression",
            description = "Whether a raw address is currently blocked on a channel. Used by send pipelines.")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Result returned"),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/check")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_VIEW})
    @PreAuthorize("hasAuthority('crm:suppression:view')")
    @EmitEvent(id = "CRM_SUPPRESSION_CHECK", apiVersion = "1")
    public ResponseEntity<Boolean> check(
            @RequestParam(name = "channel") MarketingChannel channel, @RequestParam(name = "address") String address) {
        return ResponseEntity.ok(suppressionService.isSuppressed(channel, address));
    }

    @Operation(
            summary = "Suppress an address",
            description = "Hard-block an address from marketing. Idempotent: re-adding returns the existing entry.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Address suppressed",
                content = @Content(schema = @Schema(implementation = SuppressionEntryResponse.class))),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_MANAGE})
    @PreAuthorize("hasAuthority('crm:suppression:manage')")
    @EmitEvent(id = "CRM_SUPPRESSION_ADD", apiVersion = "1")
    public ResponseEntity<SuppressionEntryResponse> add(@Valid @RequestBody AddSuppressionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(suppressionService.add(request));
    }

    @Operation(summary = "Lift a suppression", description = "Remove a suppression entry")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Suppression lifted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Suppression entry not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/{suppressionId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SUPPRESSION_MANAGE})
    @PreAuthorize("hasAuthority('crm:suppression:manage')")
    @EmitEvent(id = "CRM_SUPPRESSION_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> remove(@PathVariable UUID suppressionId) {
        suppressionService.remove(suppressionId);
        return ResponseEntity.noContent().build();
    }
}
