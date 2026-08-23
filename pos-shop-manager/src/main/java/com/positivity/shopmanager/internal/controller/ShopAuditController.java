package com.positivity.shopmanager.internal.controller;

import com.positivity.shared.error.ApiError;
import com.positivity.shopmanager.internal.dto.ShopAuditEntryResponse;
import com.positivity.shopmanager.internal.dto.ShopAuditFilter;
import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.service.ShopAuditService;
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
import org.springdoc.core.annotations.ParameterObject;
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
    @PreAuthorize(
            "hasAnyAuthority('" + ShopPermissions.SCHEDULE_VIEW + "', '" + ShopPermissions.APPOINTMENTS_VIEW + "')")
    @Operation(operationId = "searchShopAudit", summary = "Search the Shop Audit Trail", description = """
                    Searches the immutable shop audit trail of schedule and assignment changes, returning matching \
                    entries in reverse-chronological order with actor, event type, change summary and reason.
                    Use this tool when investigating who changed a schedule or assignment and why; use \
                    getShopAuditEntry instead when the audit entry id is already known.
                    Preconditions: at least one filter criterion (workorderId, appointmentId, mechanicId, \
                    actorUserId, eventType or locationId) must be supplied; unbounded scans are rejected.
                    Required inputs: any combination of the filter fields plus optional fromDateTime and \
                    toDateTime, which default to the last 90 days ending now; eventType is one of \
                    SCHEDULE_CREATED, SCHEDULE_UPDATED, SCHEDULE_CANCELLED, ASSIGNMENT_CREATED or \
                    ASSIGNMENT_REMOVED.
                    No events are emitted and no state changes; this is a read-only projection of records retained \
                    for seven years.
                    Returns 400 when no filter criterion is provided.
                    """)
    @ApiResponse(responseCode = "200", description = "Audit entries returned")
    @ApiResponse(
            responseCode = "400",
            description = "No filter criteria provided",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public @NonNull List<ShopAuditEntryResponse> searchAudit(@ParameterObject @ModelAttribute ShopAuditFilter filter) {
        return shopAuditService.search(filter);
    }

    /**
     * Retrieve a single audit entry by its UUID.
     *
     * <p>
     * Returns 200 with the entry, or 404 if it does not exist.
     */
    @GetMapping("/{id}")
    @PreAuthorize(
            "hasAnyAuthority('" + ShopPermissions.SCHEDULE_VIEW + "', '" + ShopPermissions.APPOINTMENTS_VIEW + "')")
    @Operation(operationId = "getShopAuditEntry", summary = "Get a Shop Audit Entry by ID", description = """
                    Returns a single immutable shop audit entry, including actor, event type, change summary, \
                    change patch and reason fields.
                    Use this tool when the audit entry id is already known; use searchShopAudit instead to find \
                    entries by workorder, appointment, mechanic, actor, event type or location.
                    Preconditions: the audit entry must exist; entries are never updated or deleted once written.
                    Required inputs: id (UUID) as a path parameter; there is no request body.
                    No events are emitted and no state changes; this is a read-only projection.
                    Returns 404 when no audit entry exists for the supplied id.
                    """)
    @ApiResponse(responseCode = "200", description = "Audit entry returned")
    @ApiResponse(
            responseCode = "403",
            description = "Insufficient authority",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Audit entry not found",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public @NonNull ResponseEntity<ShopAuditEntryResponse> getAuditById(@PathVariable @NonNull UUID id) {
        return shopAuditService
                .findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
