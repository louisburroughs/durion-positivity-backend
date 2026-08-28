package com.positivity.inventory.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.ConvertPurchaseSuggestionsResponse;
import com.positivity.inventory.internal.dto.purchasesuggestion.DismissPurchaseSuggestionRequest;
import com.positivity.inventory.internal.dto.purchasesuggestion.PurchaseSuggestionResponse;
import com.positivity.inventory.internal.replenishment.service.PurchaseSuggestionService;
import com.positivity.inventory.internal.security.InventoryPermissionRegistry;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
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
            summary = "List Purchase Suggestions",
            description = """
                    Returns a page of replenishment purchase suggestions, newest first, with their lifecycle \
                    status, suggested quantity, vendor and pricing snapshot.
                    Use this tool to discover suggestionIds or review what the replenishment scan proposed; use \
                    getPurchaseSuggestion instead when the id is already known.
                    Preconditions: none; suggestions are created only by the replenishment scan, so an empty page \
                    means the scan proposed nothing matching the filters.
                    Required inputs: none; status (SUGGESTED, ACCEPTED, CONVERTED or DISMISSED), sku and \
                    locationId are optional filters, and the page size defaults to 20.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 400 when status is not a known lifecycle value, and 200 with an empty page when \
                    nothing matches.
                    """,
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
            @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return ResponseEntity.ok(purchaseSuggestionService.listPurchaseSuggestions(status, sku, locationId, pageable));
    }

    @GetMapping("/{suggestionId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:on_hand:view"})
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.INVENTORY_VIEW + "')")
    @Operation(
            operationId = "getPurchaseSuggestion",
            summary = "Get Purchase Suggestion",
            description = """
                    Returns one purchase suggestion with its status, suggested quantity, vendor reference, feed \
                    price and, when converted, the created purchase order id.
                    Use this tool when the suggestionId is already known; use listPurchaseSuggestions instead to \
                    search by status, SKU or destination location.
                    Preconditions: the purchase suggestion must exist.
                    Required inputs: suggestionId (UUIDv7) path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no purchase suggestion exists for the supplied id.
                    """,
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
            summary = "Accept Purchase Suggestion",
            description = """
                    Accepts a purchase suggestion, moving it from SUGGESTED to ACCEPTED as the human-mandatory \
                    gate the replenishment scan never crosses on its own.
                    Use this tool when a reviewer approves proposed replenishment; do not use \
                    convertPurchaseSuggestions before acceptance, and use dismissPurchaseSuggestion instead to \
                    reject the proposal.
                    Preconditions: the suggestion must exist and be in SUGGESTED status.
                    Required inputs: suggestionId (UUIDv7) path parameter; there is no request body.
                    Emits an INVENTORY_PURCHASE_SUGGESTION_ACCEPT event; no purchase order is created and no \
                    spend is committed, that happens only through convertPurchaseSuggestions and the PO approval \
                    workflow.
                    Returns 404 when the suggestion does not exist, and 409 when it is not SUGGESTED because it \
                    was already accepted, converted or dismissed.
                    """,
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
            summary = "Dismiss Purchase Suggestion",
            description = """
                    Dismisses a purchase suggestion with a recorded reason, moving it to the terminal DISMISSED \
                    status.
                    Use this tool to reject one specific proposal; dismissal is per-instance, so use the \
                    replenishment policy snooze instead to suppress future suggestions for the SKU, and do not \
                    use acceptPurchaseSuggestion, which approves the proposal.
                    Preconditions: the suggestion must exist and be in SUGGESTED or ACCEPTED status.
                    Required inputs: suggestionId (UUIDv7) path parameter and a body with reason, non-blank and \
                    at most 255 characters.
                    Emits an INVENTORY_PURCHASE_SUGGESTION_DISMISS event and stores the dismissal reason on the \
                    suggestion; the replenishment scan may still raise a new suggestion later.
                    Returns 404 when the suggestion does not exist, 409 when it is already CONVERTED or \
                    DISMISSED, and 400 when the reason is missing or blank.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Why this suggestion is being rejected; recorded on the suggestion.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = DismissPurchaseSuggestionRequest.class),
                                            examples = @ExampleObject(name = "Dismissal with reason", value = """
                                                                    {"reason":"Vendor contract under renegotiation"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    DismissPurchaseSuggestionRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        return ResponseEntity.ok(
                purchaseSuggestionService.dismissPurchaseSuggestion(suggestionId, request.getReason(), actorUserId));
    }

    @PostMapping("/convert")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"inventory:replenishment:manage", "order:purchase_order:create"})
    // Both halves still apply: converting a suggestion is a replenishment decision that places a
    // purchase order, so the actor must be allowed to do each. The ordering half moved to the
    // order domain with the aggregate (CAP-320 #1334) and is named as a literal because it is
    // another module's permission — pos-inventory neither declares nor registers it.
    @PreAuthorize("hasAuthority('" + InventoryPermissionRegistry.REPLENISHMENT_MANAGE
            + "') and hasAuthority('order:purchase_order:create')")
    @EmitEvent(id = "INVENTORY_PURCHASE_SUGGESTION_CONVERT", apiVersion = "1")
    @Operation(
            operationId = "convertPurchaseSuggestions",
            summary = "Convert Purchase Suggestions To Purchase Order",
            description = """
                    Converts ACCEPTED purchase suggestions into one multi-line DRAFT purchase order, stamping \
                    each suggestion CONVERTED with the created order's id; a line is keyed in the vendor's pack \
                    UoM when the suggestion captured a pack size the quantity divides evenly by.
                    Use this tool after acceptPurchaseSuggestion to turn approved replenishment into an order; do \
                    not use createPurchaseOrder, the manual path that ignores suggestions, and note that \
                    conversion never approves spend, so approvePurchaseOrder must still run on the DRAFT order.
                    Preconditions: every listed suggestion must exist, be ACCEPTED, carry a vendor reference and \
                    a feed unit cost, all must share one vendor, and all must resolve to a single ship-to site.
                    Required inputs: suggestionIds (non-empty list of UUIDs); duplicates are collapsed, and the \
                    caller needs both inventory:replenishment:manage and inventory:purchase_order:create.
                    Emits an INVENTORY_PURCHASE_SUGGESTION_CONVERT event and creates the DRAFT order through the \
                    standard purchase order path, with the latest expected date among the suggestions as the \
                    expected delivery date.
                    Returns 404 when a listed suggestion does not exist, 422 when a suggestion is not ACCEPTED, \
                    lacks a vendor or unit cost, or the suggestions mix vendors or ship-to sites, and 400 when \
                    suggestionIds is empty.
                    """,
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
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The ACCEPTED suggestions to fold into a single DRAFT purchase order; all must"
                                            + " share one vendor.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ConvertPurchaseSuggestionsRequest.class),
                                            examples =
                                                    @ExampleObject(name = "Two suggestions, one vendor", value = """
                                                                    {"suggestionIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a30",
                                                                      "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    ConvertPurchaseSuggestionsRequest request) {
        String actorUserId = SecurityContextHelper.getCurrentUsername()
                .orElseThrow(() -> new IllegalStateException(NO_CURRENT_USER));
        ConvertPurchaseSuggestionsResponse response =
                purchaseSuggestionService.convertPurchaseSuggestions(request, actorUserId);
        return ResponseEntity.status(201).body(response);
    }
}
