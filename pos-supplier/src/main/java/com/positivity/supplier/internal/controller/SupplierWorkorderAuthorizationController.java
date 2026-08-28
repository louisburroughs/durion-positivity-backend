package com.positivity.supplier.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.shared.error.ApiError;
import com.positivity.supplier.internal.domain.model.FleetContract;
import com.positivity.supplier.internal.domain.model.FleetPolicy;
import com.positivity.supplier.internal.domain.model.FleetVehicle;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import com.positivity.supplier.internal.security.SupplierPermissions;
import com.positivity.supplier.internal.workorderauth.service.FleetLookupRunner;
import com.positivity.supplier.internal.workorderauth.service.WorkorderAuthorizationRunner;
import com.positivity.supplier.internal.workorderauth.service.model.FleetAuthorizationRequestBody;
import com.positivity.supplier.internal.workorderauth.service.model.FleetAuthorizationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
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
 * Fleet workorder authorization (CAP-323 #1229).
 *
 * <h2>Why a shop needs each of these</h2>
 *
 * The lookups come first in time: a vehicle arrives, somebody checks whether the fleet knows it and
 * what contract covers it, and only then is authorization requested for specific work. Exposing the
 * lookups separately is what makes the authorization request something a service advisor can fill in
 * correctly rather than guess at.
 *
 * <p>The authorization request is the commercial act, and the reason it is not retried anywhere in
 * this module: asking twice can open two authorizations against one contract.
 */
@RestController
@RequestMapping("/v1/supplier/fleet")
@RequiredArgsConstructor
@Tag(name = "Supplier Fleet Authorization", description = "Fleet workorder authorization and lookups")
public class SupplierWorkorderAuthorizationController {

    private final WorkorderAuthorizationRunner authorizationRunner;
    private final FleetLookupRunner fleetLookupService;

    @PostMapping("/{supplierRef}/authorizations")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:request"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZE + "')")
    @EmitEvent(id = "SUPPLIER_WORKORDER_AUTHORIZATION_REQUEST", apiVersion = "1")
    @Operation(
            operationId = "requestFleetWorkorderAuthorization",
            summary = "Request Fleet Authorization For A Workorder",
            description = """
                    Asks a vendor's fleet program to authorize the work on a workorder, and records what it \
                    said.
                    Use this tool once the vehicle and the work are known; do not call it repeatedly to check \
                    for an answer, because asking again can open a second authorization against the same \
                    contract — use getFleetWorkorderAuthorization to read the outcome instead.
                    Preconditions: the vendor profile must be enabled with a workorder-authorization binding and \
                    a billing account number, and the request must identify the vehicle by vendor vehicle id, \
                    license plate, VIN or fleet number.
                    Required inputs: supplierRef path parameter, workorderId, and at least one vehicle \
                    identifier; contractId, odometerValue and lines are optional.
                    Emits a SUPPLIER_WORKORDER_AUTHORIZATION_REQUEST event, and on a decision publishes \
                    supplier.workorderauth.granted or supplier.workorderauth.denied for the workorder domain.
                    Returns 200 with the authorization, whose status is GRANTED, DENIED, NOT_FOUND, PENDING \
                    when the vendor accepted the request and will decide later, or MANUAL_REVIEW when no \
                    decision could be obtained at all; 400 when no vehicle identifier is given; 404 when the \
                    vendor profile is unknown; and 409 when the profile is disabled or has no \
                    workorder-authorization binding configured.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @io.swagger.v3.oas.annotations.parameters.RequestBody(
            description = "The work being requested, and how the fleet vehicle is identified. At least one of "
                    + "vendorVehicleId, licensePlate, vin or fleetNumber must be given: a request that "
                    + "identifies no vehicle cannot be authorized by anyone.",
            required = true,
            content =
                    @Content(
                            schema = @Schema(implementation = FleetAuthorizationRequestBody.class),
                            examples =
                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                            name = "Two front tyres on a plated fleet vehicle",
                                            value = """
                                                    {
                                                      "workorderId": "019200aa-0000-7000-8000-0000000000c1",
                                                      "licensePlate": "ABC-123",
                                                      "contractId": "C-1",
                                                      "odometerValue": "104500",
                                                      "lines": [
                                                        {
                                                          "reference": "4001111",
                                                          "description": "Front axle tyres",
                                                          "quantity": 2,
                                                          "tirePosition": "1L"
                                                        }
                                                      ]
                                                    }
                                                    """)))
    @ApiResponse(responseCode = "200", description = "Authorization requested; see status for the outcome")
    @ApiResponse(
            responseCode = "400",
            description = "The request identifies no vehicle",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no workorder-authorization binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetAuthorizationResponse> requestFleetWorkorderAuthorization(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef,
            @Valid @RequestBody FleetAuthorizationRequestBody body) {
        return ResponseEntity.ok(authorizationRunner.requestAndProject(new SupplierRef(supplierRef), toDomain(body)));
    }

    @GetMapping("/{supplierRef}/authorizations/{workorderId}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:request"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZE + "')")
    @EmitEvent(id = "SUPPLIER_WORKORDER_AUTHORIZATION_READ", apiVersion = "1")
    @Operation(
            operationId = "getFleetWorkorderAuthorization",
            summary = "Read A Fleet Authorization",
            description = """
                    Returns the fleet authorization recorded for a workorder at a vendor, including the vendor's \
                    reason where it gave one.
                    Use this tool to check the outcome of an authorization that came back PENDING; do not call \
                    requestFleetWorkorderAuthorization again for that, because it can open a second \
                    authorization.
                    Preconditions: none.
                    Required inputs: supplierRef and workorderId path parameters.
                    Emits a SUPPLIER_WORKORDER_AUTHORIZATION_READ event.
                    Returns 200 with the authorization, and 404 when the vendor profile is unknown or no \
                    authorization was ever requested for this workorder.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The recorded authorization")
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown, or no authorization recorded for this workorder",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetAuthorizationResponse> getFleetWorkorderAuthorization(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef,
            @Parameter(description = "Workorder the authorization concerns", required = true) @PathVariable
                    java.util.UUID workorderId) {
        return authorizationRunner
                .findProjection(new SupplierRef(supplierRef), workorderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{supplierRef}/vehicles/{vehicleIdent}")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:request"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZE + "')")
    @EmitEvent(id = "SUPPLIER_FLEET_VEHICLE_LOOKUP", apiVersion = "1")
    @Operation(
            operationId = "lookupFleetVehicle",
            summary = "Look Up A Fleet Vehicle",
            description = """
                    Asks a vendor's fleet program what it knows about a vehicle, by license plate, VIN, fleet \
                    number or the vendor's own vehicle id.
                    Use this tool before requesting authorization, to confirm the vehicle is on a fleet contract \
                    and to obtain the vendor vehicle id; do not treat the answer as authorization, because only \
                    requestFleetWorkorderAuthorization obtains that.
                    Preconditions: the vendor profile must be enabled with a workorder-authorization binding.
                    Required inputs: supplierRef and vehicleIdent path parameters.
                    Emits a SUPPLIER_FLEET_VEHICLE_LOOKUP event.
                    Returns 200 with the vehicle as the fleet holds it, 404 when the fleet does not know the \
                    vehicle or the vendor profile is unknown, 409 when the profile is disabled or unconfigured, \
                    and 422 when the vendor could not be reached or answered unreadably.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The vehicle as the fleet holds it")
    @ApiResponse(
            responseCode = "404",
            description = "The fleet does not know this vehicle, or the vendor profile is unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no workorder-authorization binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The vendor could not be reached or answered unreadably",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<FleetVehicle> lookupFleetVehicle(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef,
            @Parameter(description = "Plate, VIN, fleet number or vendor vehicle id", required = true) @PathVariable
                    String vehicleIdent) {
        return fleetLookupService
                .findVehicle(new SupplierRef(supplierRef), vehicleIdent)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{supplierRef}/contracts")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:request"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZE + "')")
    @EmitEvent(id = "SUPPLIER_FLEET_CONTRACT_LIST", apiVersion = "1")
    @Operation(
            operationId = "listFleetContracts",
            summary = "List Fleet Contracts",
            description = """
                    Lists the fleet contracts this service provider may claim work under at a vendor.
                    Use this tool to populate a contract choice before requesting authorization; do not use the \
                    contract dates to decide whether work is covered, because only the vendor decides that when \
                    authorization is requested.
                    Preconditions: the vendor profile must be enabled with a workorder-authorization binding.
                    Required inputs: supplierRef path parameter.
                    Emits a SUPPLIER_FLEET_CONTRACT_LIST event.
                    Returns 200 with the contracts as stated, 404 when the vendor profile is unknown, 409 when \
                    the profile is disabled or unconfigured, and 422 when the vendor could not be reached.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The fleet contracts as stated")
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no workorder-authorization binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The vendor could not be reached or answered unreadably",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<FleetContract>> listFleetContracts(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef) {
        return ResponseEntity.ok(fleetLookupService.listContracts(new SupplierRef(supplierRef)));
    }

    @GetMapping("/{supplierRef}/policies")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:request"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZE + "')")
    @EmitEvent(id = "SUPPLIER_FLEET_POLICY_LIST", apiVersion = "1")
    @Operation(
            operationId = "listFleetPolicies",
            summary = "List Fleet Policies",
            description = """
                    Lists what a fleet program says it will and will not pay for.
                    Use this tool to narrow what is offered to a fleet customer; do not use it as permission, \
                    because it is advisory and only requestFleetWorkorderAuthorization obtains a decision.
                    Preconditions: the vendor profile must be enabled with a workorder-authorization binding.
                    Required inputs: supplierRef path parameter.
                    Emits a SUPPLIER_FLEET_POLICY_LIST event.
                    Returns 200 with the policies as stated — an empty list when the vendor states none or \
                    answers in a shape this adapter does not recognise — 404 when the vendor profile is \
                    unknown, 409 when the profile is disabled or unconfigured, and 422 when the vendor could \
                    not be reached.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "The fleet policies as stated")
    @ApiResponse(
            responseCode = "404",
            description = "Vendor profile unknown",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "409",
            description = "Vendor profile disabled, or no workorder-authorization binding configured",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(
            responseCode = "422",
            description = "The vendor could not be reached",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<List<FleetPolicy>> listFleetPolicies(
            @Parameter(description = "Vendor profile alias", required = true) @PathVariable String supplierRef) {
        return ResponseEntity.ok(fleetLookupService.listPolicies(new SupplierRef(supplierRef)));
    }

    @GetMapping("/authorizations/reviews")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"supplier:workorderauth:review"})
    @PreAuthorize("hasAuthority('" + SupplierPermissions.WORKORDER_AUTHORIZATION_REVIEW + "')")
    @EmitEvent(id = "SUPPLIER_WORKORDER_AUTHORIZATION_REVIEW_LIST", apiVersion = "1")
    @Operation(
            operationId = "listFleetAuthorizationsNeedingReview",
            summary = "List Fleet Authorizations Needing Review",
            description = """
                    Lists fleet authorizations and completions that could not be resolved with a vendor and are \
                    waiting on a person, newest first.
                    Use this tool to work the queue of stuck fleet jobs; the reviewReason says what went wrong and \
                    approvalStatus distinguishes an authorization nobody could obtain from a completion nobody \
                    could get signed off. Do not read a row here as a vendor decision — nothing in this list has \
                    been refused by a fleet, only left unresolved — and do not use it to check one workorder, \
                    because getFleetWorkorderAuthorization answers that directly instead.
                    Preconditions: none.
                    Required inputs: none; limit defaults to 100.
                    Emits a SUPPLIER_WORKORDER_AUTHORIZATION_REVIEW_LIST event.
                    Returns 200 with the rows waiting on a person, which is an empty list when nothing is stuck.
                    """,
            tags = {"Supplier Fleet Authorization"})
    @ApiResponse(responseCode = "200", description = "Authorizations and completions waiting on a person")
    public ResponseEntity<List<FleetAuthorizationResponse>> listFleetAuthorizationsNeedingReview(
            @Parameter(description = "Maximum rows to return") @RequestParam(defaultValue = "100") int limit) {
        return ResponseEntity.ok(authorizationRunner.findNeedingReview(limit));
    }

    /**
     * Builds the canonical request, where the "identifies a vehicle" rule is enforced.
     *
     * <p>Mapped here rather than on the request body so the contract package holds no dependency on
     * the internal domain: the DTO is a shape on the wire, and the rule about what makes a valid
     * request belongs with the model that has to honour it.
     */
    private static WorkorderAuthorizationRequest toDomain(FleetAuthorizationRequestBody body) {
        List<WorkorderAuthorizationRequest.Line> lines = new java.util.ArrayList<>();
        for (FleetAuthorizationRequestBody.Line line : body.linesOrEmpty()) {
            lines.add(new WorkorderAuthorizationRequest.Line(
                    line.reference(), line.description(), line.quantity(), line.tirePosition()));
        }
        return new WorkorderAuthorizationRequest(
                body.workorderId(),
                body.vendorVehicleId(),
                body.licensePlate(),
                body.vin(),
                body.fleetNumber(),
                body.contractId(),
                body.odometerValue(),
                lines);
    }
}
