package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.DismissPurchaseSuggestionRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.inventory.service.PurchaseSuggestionService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
 * Purchase suggestion lifecycle endpoints (odoo-parity F4, issue #1044; plan D-3).
 * Suggestions are created by the replenishment scan only; these endpoints cover the
 * human decisions: accept, dismiss (with mandatory reason), and convert into a DRAFT
 * purchase order. Convert requires BOTH {@code inventory:replenishment:manage} and
 * {@code inventory:purchase_order:create} (dual authority, mirroring the cross-dock
 * endpoint) because it spends against the PO domain.
 */
@RestController
@RequestMapping("/v1/inventory/purchase-suggestions")
@RequiredArgsConstructor
@Tag(name = "Purchase Suggestions", description = "Purchase suggestion listing and lifecycle endpoints")
public class PurchaseSuggestionController {

    private static final String NO_CURRENT_USER = "No current user";

    private final PurchaseSuggestionService purchaseSuggestionService;

    @GetMapping
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "listPurchaseSuggestions",
            summary = "List purchase suggestions",
            description =
                    "Lists purchase suggestions, optionally filtered by status, SKU, and destination" + " location.",
            tags = {"Purchase Suggestions"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase suggestions returned (paged)",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(description = "Page of purchase suggestions")))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<PurchaseSuggestionResponse>> listPurchaseSuggestions(
            @Parameter(description = "Lifecycle status filter (SUGGESTED, ACCEPTED, CONVERTED, DISMISSED)")
                    @RequestParam(required = false)
                    String status,
            @Parameter(description = "Stock item (SKU) filter") @RequestParam(required = false) String sku,
            @Parameter(description = "Destination location filter") @RequestParam(required = false) UUID locationId,
            @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(purchaseSuggestionService.listPurchaseSuggestions(status, sku, locationId, pageable));
    }

    @GetMapping("/{suggestionId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "getPurchaseSuggestion",
            summary = "Get purchase suggestion",
            description = "Retrieves one purchase suggestion by identifier.",
            tags = {"Purchase Suggestions"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase suggestion returned",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseSuggestionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required view authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase suggestion not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseSuggestionResponse> getPurchaseSuggestion(
            @Parameter(description = "Purchase suggestion identifier", required = true) @PathVariable
                    UUID suggestionId) {
        return ResponseEntity.ok(purchaseSuggestionService.getPurchaseSuggestion(suggestionId));
    }

    @PostMapping("/{suggestionId}/accept")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_PURCHASE_SUGGESTION_ACCEPT", apiVersion = "1")
    @Operation(
            operationId = "acceptPurchaseSuggestion",
            summary = "Accept a purchase suggestion",
            description = "Human-mandatory accept gate (plan D-3): transitions SUGGESTED → ACCEPTED, making the"
                    + " suggestion eligible for conversion into a DRAFT purchase order. The replenishment scan"
                    + " never accepts suggestions.",
            tags = {"Purchase Suggestions"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase suggestion accepted",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseSuggestionResponse.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required replenishment manage authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase suggestion not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Suggestion is not in SUGGESTED status",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseSuggestionResponse> acceptPurchaseSuggestion(
            @Parameter(description = "Purchase suggestion identifier", required = true) @PathVariable
                    UUID suggestionId) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(purchaseSuggestionService.acceptPurchaseSuggestion(suggestionId, actorUserId));
    }

    @PostMapping("/{suggestionId}/dismiss")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE + "')")
    @EmitEvent(id = "INVENTORY_PURCHASE_SUGGESTION_DISMISS", apiVersion = "1")
    @Operation(
            operationId = "dismissPurchaseSuggestion",
            summary = "Dismiss a purchase suggestion",
            description = "Dismisses a SUGGESTED or ACCEPTED suggestion with a mandatory reason. Dismissal is"
                    + " terminal and per-instance; suppressing future suggestions is the policy snooze's job.",
            tags = {"Purchase Suggestions"})
    @ApiResponse(
            responseCode = "200",
            description = "Purchase suggestion dismissed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = PurchaseSuggestionResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Missing or blank dismissal reason",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks required replenishment manage authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Purchase suggestion not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Suggestion is already CONVERTED or DISMISSED",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<PurchaseSuggestionResponse> dismissPurchaseSuggestion(
            @Parameter(description = "Purchase suggestion identifier", required = true) @PathVariable UUID suggestionId,
            @Valid @RequestBody DismissPurchaseSuggestionRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(
                purchaseSuggestionService.dismissPurchaseSuggestion(suggestionId, request.getReason(), actorUserId));
    }

    @PostMapping("/convert")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage", "inventory:purchase_order:create"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE
            + "') and hasAuthority('inventory:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_SUGGESTION_CONVERT", apiVersion = "1")
    @Operation(
            operationId = "convertPurchaseSuggestions",
            summary = "Convert accepted purchase suggestions into a DRAFT purchase order",
            description = "Creates ONE multi-line DRAFT purchase order from ACCEPTED suggestions sharing a"
                    + " single vendor, then stamps each suggestion CONVERTED. The DRAFT order still passes the"
                    + " existing purchase order approval workflow — conversion never approves spend. Requires"
                    + " BOTH inventory:replenishment:manage AND inventory:purchase_order:create.",
            tags = {"Purchase Suggestions"})
    @ApiResponse(
            responseCode = "201",
            description = "DRAFT purchase order created and suggestions stamped CONVERTED",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = ConvertPurchaseSuggestionsResponse.class)))
    @ApiResponse(
            responseCode = "400",
            description = "Validation failure (empty suggestion list)",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "User lacks replenishment manage and/or purchase order create authority",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "A listed purchase suggestion was not found",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "Conversion precondition violated: a suggestion is not ACCEPTED, suggestions mix"
                    + " vendors or ship-to sites, or a suggestion lacks a vendor or feed price",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<ConvertPurchaseSuggestionsResponse> convertPurchaseSuggestions(
            @Valid @RequestBody ConvertPurchaseSuggestionsRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        ConvertPurchaseSuggestionsResponse response =
                purchaseSuggestionService.convertPurchaseSuggestions(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }
}
