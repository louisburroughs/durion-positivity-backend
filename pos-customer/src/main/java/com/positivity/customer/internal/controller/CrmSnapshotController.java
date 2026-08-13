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

    @Operation(operationId = "getSnapshotByParty", summary = "Get CRM Snapshot By Party", description = """
                    Returns a consolidated CRM snapshot for a party — account summary, contacts, vehicle \
                    summaries, and preferences — assembled for commercial or person parties and served from \
                    a cache when a fresh copy exists.
                    Use this tool when a caller needs the whole customer picture in one call; use getParty \
                    instead for just the identity fields, and getSnapshotByVehicle when only a vehicle id \
                    is known.
                    Preconditions: a commercial or person party must exist for the supplied partyId.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body, and the \
                    snapshotMetadata.source field reports CACHE or CRM_API depending on where the data came \
                    from.
                    Emits a CRM_SNAPSHOT_PARTY_RETRIEVE audit event; the snapshot cache may be populated \
                    but no domain state changes.
                    Returns 404 when neither a commercial nor a person party exists for the supplied \
                    partyId.
                    """)
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

    @Operation(operationId = "getBillingRules", summary = "Get Party Billing Rules", description = """
                    Returns the billing-rules reference for a party, falling back to default rules when a \
                    commercial party has no explicitly configured rules and for person parties, which have \
                    no configurable rules; enforcement of the rules belongs to downstream services.
                    Use this tool when reading how an account should be billed; use upsertBillingRules \
                    instead to change the configuration.
                    Preconditions: a commercial or person party must exist for the supplied partyId.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_SNAPSHOT_BILLING_RULES_GET audit event; no state changes occur.
                    Returns 404 when no party exists for the supplied partyId, and 200 with defaults rather \
                    than an error when rules were never configured.
                    """)
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

    @Operation(operationId = "getSnapshotByVehicle", summary = "Get CRM Snapshot By Vehicle", description = """
                    Returns the CRM snapshot of the commercial party that owns a vehicle, resolved through \
                    the vehicle-to-party association replicated from vehicle events.
                    Use this tool when only a vehicle id is known, for example at service check-in; use \
                    getSnapshotByParty instead when the party id is already known.
                    Preconditions: the vehicle must be associated with a commercial party in the local \
                    replica; recently registered vehicles may not have been replicated yet.
                    Required inputs: vehicleId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_SNAPSHOT_VEHICLE_RETRIEVE audit event; no state changes occur.
                    Returns 404 when no owning party can be resolved for the supplied vehicleId.
                    """)
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
