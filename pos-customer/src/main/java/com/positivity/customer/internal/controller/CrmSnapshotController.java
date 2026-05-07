package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.snapshot.BillingRuleRef;
import com.positivity.customer.internal.dto.snapshot.CrmSnapshotDTO;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.CrmVehicleService;
import com.positivity.customer.service.PartyService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST endpoints for CRM snapshot retrieval - CAP:092 Story #99
 */
@Tag(name = "CRM Snapshots", description = "Consolidated CRM data snapshots")
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {CrmPermissionRegistry.PARTY_VIEW})
@RequestMapping("/v1/crm/snapshot")
public class CrmSnapshotController {

    private static final Logger log = LoggerFactory.getLogger(CrmSnapshotController.class);

    private final PartyService partyOps;
    private final CrmVehicleService vehicleOps;

    public CrmSnapshotController(@NonNull PartyService partyOps, @NonNull CrmVehicleService vehicleOps) {
        this.partyOps = partyOps;
        this.vehicleOps = vehicleOps;
    }

    @Operation(
            summary = "Fetch snapshot by party",
            description = "Returns complete party snapshot with accounts, contacts, vehicles")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Success",
                        content = @Content(schema = @Schema(implementation = CrmSnapshotDTO.class))),
                @ApiResponse(responseCode = "404", description = "Party not found", content = @Content),
                @ApiResponse(responseCode = "403", description = "Access denied", content = @Content)
            })
    @GetMapping("/party/{partyId}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CRM_SNAPSHOT_PARTY_RETRIEVE", apiVersion = "1")
    public ResponseEntity<CrmSnapshotDTO> fetchByParty(
            @Parameter(description = "Party ID", required = true) @PathVariable UUID partyId) {

        log.info("Fetching snapshot by party: {}", partyId);

        CrmSnapshotDTO result = partyOps.buildSnapshotForParty(partyId);

        return result != null
                ? ResponseEntity.ok(result)
                : ResponseEntity.notFound().build();
    }

    @Operation(
            operationId = "getBillingRules",
            summary = "Get billing rules for a commercial party",
            description = "Returns the billing rule reference for a commercial party. "
                    + "Returns default billing rules when the party has no explicitly configured rules. "
                    + "Enforcement of these rules is the responsibility of downstream services.")
    @ApiResponses(
            value = {
                @ApiResponse(responseCode = "200", description = "Billing rules returned"),
                @ApiResponse(responseCode = "403", description = "Caller lacks PARTY_VIEW authority"),
                @ApiResponse(responseCode = "404", description = "Party not found")
            })
    @GetMapping("/party/{partyId}/billing-rules")
    @PreAuthorize("hasAuthority('crm:party:view')")
    @EmitEvent(id = "CRM_SNAPSHOT_BILLING_RULES_GET", apiVersion = "1")
    public ResponseEntity<BillingRuleRef> getBillingRules(
            @Parameter(description = "Party ID (UUID)") @PathVariable UUID partyId) {
        BillingRuleRef rules = partyOps.getBillingRulesForParty(partyId);
        if (rules == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(rules);
    }

    @Operation(summary = "Fetch snapshot by vehicle", description = "Returns party snapshot based on vehicle ownership")
    @ApiResponses(
            value = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Success",
                        content = @Content(schema = @Schema(implementation = CrmSnapshotDTO.class))),
                @ApiResponse(responseCode = "404", description = "Vehicle or owner not found", content = @Content),
                @ApiResponse(responseCode = "403", description = "Access denied", content = @Content)
            })
    @GetMapping("/vehicle/{vehicleId}")
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.PARTY_VIEW + "')")
    @EmitEvent(id = "CRM_SNAPSHOT_VEHICLE_RETRIEVE", apiVersion = "1")
    public ResponseEntity<CrmSnapshotDTO> fetchByVehicle(
            @Parameter(description = "Vehicle ID", required = true) @PathVariable UUID vehicleId) {

        log.info("Fetching snapshot by vehicle: {}", vehicleId);

        CrmSnapshotDTO result = vehicleOps.buildSnapshotForVehicleOwner(vehicleId);

        return result != null
                ? ResponseEntity.ok(result)
                : ResponseEntity.notFound().build();
    }
}
