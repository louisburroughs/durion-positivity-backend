package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.pick.CreateSubstituteLinkRequest;
import com.positivity.workorder.internal.dto.pick.SubstituteLinkResponse;
import com.positivity.workorder.internal.dto.pick.UpdateSubstituteLinkRequest;
import com.positivity.workorder.service.SubstituteLinkService;
import jakarta.validation.Valid;
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

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class SubstituteLinkController {

    private final SubstituteLinkService substituteLinkService;

    @PostMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_CREATE", apiVersion = "1")
    @PreAuthorize("hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    public ResponseEntity<SubstituteLinkResponse> createLink(
            @PathVariable UUID productId,
            @RequestBody @Valid CreateSubstituteLinkRequest request) {
        request.setProductId(productId);
        return ResponseEntity.status(201).body(substituteLinkService.createLink(request));
    }

    @GetMapping("/products/substitutes/{productId}")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SubstituteLinkResponse>> listLinks(@PathVariable UUID productId) {
        return ResponseEntity.ok(substituteLinkService.listLinks(productId));
    }

    @PutMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_UPDATE", apiVersion = "1")
    @PreAuthorize("hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    public ResponseEntity<SubstituteLinkResponse> updateLink(
            @PathVariable UUID productId,
            @RequestParam("linkId") UUID linkId,
            @RequestBody @Valid UpdateSubstituteLinkRequest request) {
        return ResponseEntity.ok(substituteLinkService.updateLink(linkId, request));
    }

    @DeleteMapping("/products/substitutes/{productId}")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_LINK_DELETE", apiVersion = "1")
    @PreAuthorize("hasAnyAuthority('ROLE_PRODUCT_ADMIN', 'ROLE_INVENTORY_ADMIN', 'workorder:parts:add', 'workorder:workorder:edit')")
    public ResponseEntity<SubstituteLinkResponse> deleteLink(
            @PathVariable UUID productId,
            @RequestParam("substitute") UUID substitutePartId) {
        return ResponseEntity.ok(substituteLinkService.deleteLink(productId, substitutePartId));
    }

    @PostMapping("/workorders/{workorderId}/suggestSubstitutes")
    @EmitEvent(id = "WORKORDER_SUBSTITUTE_SUGGEST", apiVersion = "1")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<SubstituteLinkResponse>> suggestSubstitutes(@PathVariable UUID workorderId) {
        return ResponseEntity.ok(substituteLinkService.suggestSubstitutes(workorderId));
    }
}