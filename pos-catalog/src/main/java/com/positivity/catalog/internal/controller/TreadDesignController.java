package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.TreadDesignCandidateDto;
import com.positivity.catalog.internal.dto.TreadDesignDto;
import com.positivity.catalog.internal.dto.TreadDesignResolveRequest;
import com.positivity.catalog.internal.enums.TreadDesignMatchState;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.TreadDesignService;
import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read and review API over MKCAT tread-design marketing enrichment (CAP-324 #1352, review added by
 * #1645).
 *
 * <p>The enrichment content itself is still vendor-supplied and has no write endpoint — it exists
 * only because a vendor said so. What {@code resolve} writes is not that content but the catalogue's
 * judgement about which product it describes, which is a decision this domain owns.
 */
@Tag(
        name = "Tread Design Enrichment",
        description = "Vendor-supplied MKCAT marketing content — names, copy per language, artwork — attached to"
                + " catalog products by matching, not by identifier. Read, plus a review action for the"
                + " matches a person has to decide.")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/catalog/tread-designs")
public class TreadDesignController {

    /** What the worklist shows when the caller does not say: everything awaiting a decision. */
    private static final String DEFAULT_MATCH_STATES = "UNMATCHED,REVIEW";

    private final TreadDesignService treadDesignService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_VIEW})
    @GetMapping("/for-product/{productId}")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_FOR_PRODUCT", apiVersion = "1")
    @Operation(
            operationId = "getTreadDesignForProduct",
            summary = "Get Vendor Tread-Design Enrichment for a Product",
            description = """
            Returns the manufacturer marketing content matched to a product — names, copy per language, artwork —
            distinguishable from catalog-owned product data because it lives here rather than on the product itself.
            Use this tool to show manufacturer marketing copy alongside a product; do not use it as a source for
            any structural or identity field, since a supplier fact never changes what a product is.
            Preconditions: the product must exist and must have matched a tread design; fuzzy matching on vendor
            brand and design names means many products, especially newly priced ones, match nothing yet.
            Required inputs: productId path parameter; there is no request body.
            Emits a CATALOG_TREAD_DESIGN_FOR_PRODUCT event; no state changes.
            Returns 404 when the product does not exist or matches no tread design — an ordinary outcome, not
            an error condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The matched design's enrichment. The candidates array is empty on this read.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TreadDesignDto.class)))
    @ApiResponse(
            responseCode = "404",
            description = "The product does not exist, or matches no tread design. The response has NO body.",
            content = @Content(schema = @Schema(hidden = true)))
    public ResponseEntity<TreadDesignDto> getTreadDesignForProduct(
            @Parameter(description = "Product to look up enrichment for.", required = true) @PathVariable
                    UUID productId) {
        return treadDesignService
                .findForProduct(productId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_VIEW})
    @GetMapping("/unmatched")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_UNMATCHED_LIST", apiVersion = "1")
    @Operation(
            operationId = "listUnmatchedTreadDesigns",
            summary = "List Tread Designs Awaiting Enrichment Review (review worklist)",
            description = """
            Returns the enrichment review worklist: tread designs in the requested match states, most recently
            changed first, each with the products the matcher scored against it and how confident it was.
            Use this tool to work a queue of enrichment decisions a person has to make; do not use it to look up
            one product's enrichment, which is getTreadDesignForProduct. A design matching nothing is an ordinary
            outcome here, not a failure of ingestion.
            Preconditions: none; an empty result means nothing is waiting in the requested states.
            Required inputs: none. matchState defaults to UNMATCHED,REVIEW — the designs actually awaiting a
            decision — and accepts any of UNMATCHED, REVIEW, MATCHED, REJECTED, DEFERRED, repeated or comma
            separated. vendorProfileId narrows the worklist to one vendor profile. page and size are optional,
            with size defaulting to 50 and capped at 200.
            Emits a CATALOG_TREAD_DESIGN_UNMATCHED_LIST event; no state changes.
            Returns 200 with an empty items array when nothing is waiting, and 400 when a match state is not one
            of the five above or the page size is out of range.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "A page of designs in the requested states, most recently changed first.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Page.class)))
    @ApiResponse(
            responseCode = "400",
            description = "An unknown match state, or a page size out of range.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<Page<TreadDesignDto>> listUnmatchedTreadDesigns(
            @Parameter(
                            description = "Match states to include. Defaults to the states awaiting a decision.",
                            array = @ArraySchema(schema = @Schema(implementation = TreadDesignMatchState.class)))
                    @RequestParam(name = "matchState", defaultValue = DEFAULT_MATCH_STATES)
                    List<TreadDesignMatchState> matchState,
            @Parameter(description = "Narrow the worklist to one vendor profile.") @RequestParam(required = false)
                    UUID vendorProfileId,
            @Parameter(description = "Zero-based page index.", schema = @Schema(type = "integer", example = "0"))
                    @RequestParam(defaultValue = "0")
                    @Min(0)
                    int page,
            @Parameter(
                            description = "Page size, 1-200.",
                            schema = @Schema(type = "integer", example = "50", defaultValue = "50"))
                    @RequestParam(defaultValue = "50")
                    @Min(1)
                    @Max(200)
                    int size) {
        List<TreadDesignMatchState> states = matchState.isEmpty()
                ? List.of(TreadDesignMatchState.UNMATCHED, TreadDesignMatchState.REVIEW)
                : matchState;
        return ResponseEntity.ok(treadDesignService.findForReview(states, vendorProfileId, PageRequest.of(page, size)));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_VIEW})
    @GetMapping("/{treadDesignId}/candidates")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_CANDIDATES_LIST", apiVersion = "1")
    @Operation(
            operationId = "listTreadDesignCandidates",
            summary = "List the Products Scored Against a Tread Design",
            description = """
            Returns every catalog product the matcher scored against one tread design, best score first, with the
            confidence tier each score fell in.
            Use this tool to show a reviewer what the matcher saw before they attach, reject or defer a design;
            do not use it as a product search, since the candidates are only ever products the design's own
            vendor has priced.
            Preconditions: the design must exist. An empty array is a real answer — nothing resembled it closely
            enough to be worth recording — and is not the same as an unknown design.
            Required inputs: treadDesignId path parameter; there is no request body.
            Emits a CATALOG_TREAD_DESIGN_CANDIDATES_LIST event; no state changes.
            Returns 404 when no such design exists.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The scored candidates, best first; empty when nothing scored above the review floor.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = TreadDesignCandidateDto.class))))
    @ApiResponse(
            responseCode = "404",
            description = "No such tread design.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<TreadDesignCandidateDto>> listTreadDesignCandidates(
            @Parameter(description = "The tread design whose candidates to list.", required = true) @PathVariable
                    UUID treadDesignId) {
        return ResponseEntity.ok(treadDesignService.findCandidates(treadDesignId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.TREAD_DESIGN_RESOLVE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.TREAD_DESIGN_RESOLVE})
    @PostMapping("/{treadDesignId}/resolve")
    @EmitEvent(id = "CATALOG_TREAD_DESIGN_RESOLVE", apiVersion = "1")
    @Operation(
            operationId = "resolveTreadDesign",
            summary = "Resolve a Tread Design Awaiting Review",
            description = """
            Records a person's decision about a tread design: ATTACH it to named products, REJECT the matcher's
            suggestions, or DEFER the decision.
            Use this tool when a reviewer has judged a worklist row; do not use it to correct the vendor's
            marketing content, which this module never edits — only the association is decided here.
            An ATTACH marks each product as manually attached, and a manual attachment is never re-pointed by a
            later automatic pass, so this is how a human decision is made to stick. A REJECT detaches nothing that
            a person attached earlier — rejecting the machine's suggestions says nothing about a human decision.
            Preconditions: the design must exist; ATTACH requires at least one existing product and none of them
            may already be manually attached to a different design.
            Required inputs: treadDesignId path parameter and a body carrying action; productIds is required for
            ATTACH and rejected otherwise, deferUntil is accepted for DEFER only, note is always optional.
            Emits a CATALOG_TREAD_DESIGN_RESOLVE event and changes the design's match state.
            Returns 400 for an action and payload that cannot go together, 404 for an unknown design or product,
            and 409 when a named product is already manually attached to a different design.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The design as it now stands, with its candidates.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = TreadDesignDto.class)))
    @ApiResponse(
            responseCode = "400",
            description = "An action and payload that cannot go together — ATTACH with no products, for instance.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "No such tread design, or a named product does not exist.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "A named product is already manually attached to a different tread design.",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<TreadDesignDto> resolveTreadDesign(
            @Parameter(description = "The tread design being resolved.", required = true) @PathVariable
                    UUID treadDesignId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The reviewer's decision: ATTACH with the products this design describes,"
                                    + " REJECT when none of the candidates is right, or DEFER to decide later."
                                    + " productIds is required for ATTACH and rejected otherwise; deferUntil is"
                                    + " accepted for DEFER only; note is always optional.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = TreadDesignResolveRequest.class),
                                            examples = @ExampleObject(name = "Attach two sizes", value = """
                                                                    {"action":"ATTACH",
                                                                     "productIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                                   "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"],
                                                                     "note":"Confirmed against the vendor's catalogue"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    TreadDesignResolveRequest request,
            Authentication authentication) {
        // The gateway is the only source of identity here (X-User -> principal name); an
        // unauthenticated call cannot reach this method, so the fallback is a defensive label
        // rather than a supported path.
        String resolvedBy =
                authentication != null && authentication.getName() != null ? authentication.getName() : "unknown";
        return ResponseEntity.ok(treadDesignService.resolve(treadDesignId, request, resolvedBy));
    }
}
