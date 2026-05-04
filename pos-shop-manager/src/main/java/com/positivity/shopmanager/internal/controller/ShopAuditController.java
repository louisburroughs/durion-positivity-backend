package com.positivity.shopmanager.internal.controller;

import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.service.ShopAuditService;
import com.positivity.shared.error.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@SecurityRequirement(name = "bearerAuth")
@RequestMapping("/v1/shop/audit")
@RequiredArgsConstructor
@Tag(name = "Shop Audit", description = "Shop schedule and assignment audit trail.")
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
    @Operation(operationId = "searchShopAudit", summary = "Search shop audit trail", description = "Searches the shop audit trail using filter criteria. At least one filter criterion is required.")
    @ApiResponse(responseCode = "200", description = "Audit entries returned")
    @ApiResponse(responseCode = "400", description = "No filter criteria provided", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Insufficient authority", content = @Content(schema = @Schema(implementation = ApiError.class)))
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
    @Operation(operationId = "getShopAuditEntry", summary = "Get shop audit entry by ID", description = "Returns a single shop audit entry by its UUID.")
    @ApiResponse(responseCode = "200", description = "Audit entry returned")
    @ApiResponse(responseCode = "403", description = "Insufficient authority", content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "404", description = "Audit entry not found", content = @Content(schema = @Schema(implementation = ApiError.class)))
    public @NonNull ResponseEntity<ShopAuditEntryResponse> getAuditById(@PathVariable @NonNull UUID id) {
        return shopAuditService
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
