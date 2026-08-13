package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.pick.CreateSubstituteLinkRequest;
import com.positivity.workorder.internal.dto.pick.SubstituteLinkResponse;
import com.positivity.workorder.internal.dto.pick.SuggestSubstitutesRequest;
import com.positivity.workorder.internal.dto.pick.UpdateSubstituteLinkRequest;
import com.positivity.workorder.service.SubstituteLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
@Tag(
        name = "Substitute Link API",
        description = "Operations for product substitute links and workorder substitute suggestions")
public class SubstituteLinkController {

    private final SubstituteLinkService substituteLinkService;

    @PostMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_CREATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_PRODUCT_ADMIN", "ROLE_INVENTORY_ADMIN", "workorder:parts:add", "workorder:workorder:edit"})
    @PreAuthorize(
            "hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    @Operation(operationId = "createSubstituteLink", summary = "Create Product Substitute Link", description = """
                    Creates an active substitute link declaring that one part may stand in for a product, with a \
                    type, priority, optional effectivity window, and an audit record of the creation.
                    Use this tool when curating the substitute catalog for a product; do not use \
                    suggestWorkorderSubstitutes, which only reads existing links to propose swaps on a workorder.
                    Preconditions: no link may already exist for the product and substitute part pair, active or \
                    not.
                    Required inputs: productId (UUID) as a path parameter (it overrides any productId in the \
                    body), substitutePartId (UUID), and substituteType (EQUIVALENT, APPROVED_ALTERNATIVE, \
                    UPGRADE, or DOWNGRADE); priority defaults to 100 and isAutoSuggest defaults to false.
                    Emits a WORKORDER_SUBSTITUTE_LINK_CREATE event and writes a CREATE row to the substitute \
                    audit trail.
                    Returns 201 with the new link, and 409 when a link already exists for the pair.
                    """)
    @ApiResponse(responseCode = "201", description = "Substitute link created")
    public ResponseEntity<SubstituteLinkResponse> createLink(
            @PathVariable UUID productId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Substitute link definition pairing a product with an allowed stand-in part.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Equivalent part",
                                                            value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a31",
                                                                     "substitutePartId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a32",
                                                                     "substituteType":"EQUIVALENT",
                                                                     "priority":10,
                                                                     "isAutoSuggest":true}
                                                                    """)))
                    @RequestBody
                    @Valid
                    CreateSubstituteLinkRequest request) {
        request.setProductId(productId);
        return ResponseEntity.status(201).body(substituteLinkService.createLink(request));
    }

    @GetMapping("/products/substitutes/{productId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    @Operation(operationId = "listSubstituteLinks", summary = "List Active Substitute Links", description = """
                    Returns the active substitute links for a product, ordered by ascending priority.
                    Use this tool when reviewing which parts may stand in for a product; do not use \
                    suggestWorkorderSubstitutes, which serves workorder-scoped suggestions instead of catalog \
                    curation.
                    Preconditions: none — a product with no active links yields an empty list, and soft-deleted \
                    links are excluded.
                    Required inputs: productId (UUID) as a path parameter.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 200 with the active links, possibly empty; no 404 is produced for unknown products.
                    """)
    @ApiResponse(responseCode = "200", description = "Active substitute links returned")
    public ResponseEntity<List<SubstituteLinkResponse>> listLinks(@PathVariable UUID productId) {
        return ResponseEntity.ok(substituteLinkService.listLinks(productId));
    }

    @PutMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_UPDATE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_PRODUCT_ADMIN", "ROLE_INVENTORY_ADMIN", "workorder:parts:add", "workorder:workorder:edit"})
    @PreAuthorize(
            "hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    @Operation(operationId = "updateSubstituteLink", summary = "Update Product Substitute Link", description = """
                    Updates an existing substitute link's type, priority, auto-suggest flag, or effectivity \
                    window under optimistic version control, writing an UPDATE row to the audit trail.
                    Use this tool when tuning an existing link; do not use createSubstituteLink, which creates a \
                    new product-to-part pairing, or deleteSubstituteLink, which deactivates one.
                    Preconditions: the link identified by linkId must exist, and the supplied version must equal \
                    the link's current version.
                    Required inputs: linkId (UUID) as a query parameter and version (integer) in the body; \
                    substituteType, priority, isAutoSuggest, effectiveFrom, and effectiveTo are optional and \
                    left unchanged when null.
                    Emits a WORKORDER_SUBSTITUTE_LINK_UPDATE event and records before and after payloads in the \
                    substitute audit trail.
                    Returns 404 when no link exists for linkId, and 409 when the supplied version is stale.
                    """)
    @ApiResponse(responseCode = "200", description = "Substitute link updated")
    public ResponseEntity<SubstituteLinkResponse> updateLink(
            @PathVariable UUID productId,
            @RequestParam("linkId") UUID linkId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Fields to change on the link plus the expected current version.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Raise priority",
                                                            value = """
                                                                    {"priority":5,"isAutoSuggest":true,"version":2}
                                                                    """)))
                    @RequestBody
                    @Valid
                    UpdateSubstituteLinkRequest request) {
        return ResponseEntity.ok(substituteLinkService.updateLink(linkId, request));
    }

    @DeleteMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_PRODUCT_ADMIN", "ROLE_INVENTORY_ADMIN", "workorder:parts:add", "workorder:workorder:edit"})
    @PreAuthorize(
            "hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    @Operation(operationId = "deleteSubstituteLink", summary = "Deactivate Product Substitute Link", description = """
                    Deactivates the substitute link between a product and a substitute part — a soft delete that \
                    sets isActive to false and preserves the row and its audit history.
                    Use this tool when a part may no longer stand in for a product; do not use \
                    updateSubstituteLink, which changes link attributes while keeping it active.
                    Preconditions: a link must exist for the product and substitute part pair; already-inactive \
                    links are still found and re-saved.
                    Required inputs: productId (UUID) as a path parameter and substitute (UUID of the substitute \
                    part) as a query parameter; there is no request body.
                    Emits a WORKORDER_SUBSTITUTE_LINK_DELETE event and writes a DELETE row to the substitute \
                    audit trail.
                    Returns 200 with the deactivated link, and 404 when no link exists for the pair.
                    """)
    @ApiResponse(responseCode = "200", description = "Substitute link deactivated")
    public ResponseEntity<SubstituteLinkResponse> deleteLink(
            @PathVariable UUID productId, @RequestParam("substitute") UUID substitutePartId) {
        return ResponseEntity.ok(substituteLinkService.deleteLink(productId, substitutePartId));
    }

    @PostMapping("/workorders/{workorderId}/suggestSubstitutes")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_SUGGEST", apiVersion = "1")
    @Operation(
            operationId = "suggestWorkorderSubstitutes",
            summary = "Suggest Substitute Parts for Workorder",
            description = """
                    Suggests substitute parts for a workorder by returning the active substitute links of the \
                    part named in the request, ordered by ascending priority.
                    Use this tool when a pick is short and a stand-in part is needed; do not use \
                    listSubstituteLinks, which is the catalog view keyed by product rather than a workorder \
                    suggestion.
                    Preconditions: suggestions require a partId scope — without a body or partId the result is \
                    currently an empty list, because workorder-wide suggestions are not implemented.
                    Required inputs: workorderId (UUID) as a path parameter; the body is optional and carries \
                    partId (UUID) to scope suggestions to that part's links.
                    Emits a WORKORDER_SUBSTITUTE_SUGGEST audit event; no substitution is applied and no state \
                    changes — this is a read-only suggestion.
                    Returns 200 with the suggested links, empty when no partId is given or no active links exist.
                    """)
    @ApiResponse(responseCode = "200", description = "Substitute suggestions returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SubstituteLinkResponse>> suggestSubstitutes(
            @PathVariable UUID workorderId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Optional scope naming the workorder part to suggest substitutes for.",
                            required = false,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Scoped to a part",
                                                            value = """
                                                                    {"partId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a33"}
                                                                    """)))
                    @RequestBody(required = false)
                    @Valid
                    SuggestSubstitutesRequest request) {
        UUID partId = request != null ? request.getPartId() : null;
        return ResponseEntity.ok(substituteLinkService.suggestSubstitutes(workorderId, partId));
    }
}
