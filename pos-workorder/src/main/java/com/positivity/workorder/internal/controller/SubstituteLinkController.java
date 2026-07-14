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
    public ResponseEntity<SubstituteLinkResponse> createLink(
            @PathVariable UUID productId, @RequestBody @Valid CreateSubstituteLinkRequest request) {
        request.setProductId(productId);
        return ResponseEntity.status(201).body(substituteLinkService.createLink(request));
    }

    @GetMapping("/products/substitutes/{productId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
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
    public ResponseEntity<SubstituteLinkResponse> updateLink(
            @PathVariable UUID productId,
            @RequestParam("linkId") UUID linkId,
            @RequestBody @Valid UpdateSubstituteLinkRequest request) {
        return ResponseEntity.ok(substituteLinkService.updateLink(linkId, request));
    }

    @DeleteMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_DELETE", apiVersion = "1")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_PRODUCT_ADMIN", "ROLE_INVENTORY_ADMIN", "workorder:parts:add", "workorder:workorder:edit"})
    @PreAuthorize(
            "hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    public ResponseEntity<SubstituteLinkResponse> deleteLink(
            @PathVariable UUID productId, @RequestParam("substitute") UUID substitutePartId) {
        return ResponseEntity.ok(substituteLinkService.deleteLink(productId, substitutePartId));
    }

    @PostMapping("/workorders/{workorderId}/suggestSubstitutes")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_SUGGEST", apiVersion = "1")
    @Operation(
            summary = "Suggest workorder substitutes",
            description = "Suggest substitute parts for a workorder based on the parts currently required. "
                    + "An optional request body scopes the suggestions to a single part.")
    @ApiResponse(responseCode = "200", description = "Substitute suggestions returned")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(name = "bearerAuth")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SubstituteLinkResponse>> suggestSubstitutes(
            @PathVariable UUID workorderId, @RequestBody(required = false) @Valid SuggestSubstitutesRequest request) {
        UUID partId = request != null ? request.getPartId() : null;
        return ResponseEntity.ok(substituteLinkService.suggestSubstitutes(workorderId, partId));
    }
}
