package com.positivity.shopmanager.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.service.ShopAuditService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for querying the shop audit trail.
 *
 * <p>
 * Immutability policy: no DELETE, PATCH, or PUT endpoints are defined for audit
 * records.
 */
@RestController
@RequestMapping("/v1/shop/audit")
@RequiredArgsConstructor
public class ShopAuditController {

    private final ShopAuditService shopAuditService;

    /**
     * Search the shop audit trail.
     *
     * <p>
     * At least one filter criterion is required; returns 400 if none are provided.
     * Returns 200 with matching entries in reverse-chronological order.
     */
    @GetMapping
    @PreAuthorize("hasAnyAuthority('shop:schedule:view', 'appointments:view')")
    @EmitEvent(id = "SHOPMGR_AUDIT_SEARCH", apiVersion = "1")
    public @NonNull List<ShopAuditEntryResponse> searchAudit(@ModelAttribute ShopAuditFilter filter) {
        return shopAuditService.search(filter);
    }

    /**
     * Retrieve a single audit entry by its UUID.
     *
     * <p>
     * Returns 200 with the entry, or 404 if it does not exist.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('shop:schedule:view', 'appointments:view')")
    @EmitEvent(id = "SHOPMGR_AUDIT_GET_BY_ID", apiVersion = "1")
    public @NonNull ResponseEntity<ShopAuditEntryResponse> getAuditById(@PathVariable UUID id) {
        return shopAuditService
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
