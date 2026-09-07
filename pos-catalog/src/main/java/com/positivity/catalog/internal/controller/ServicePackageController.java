package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.ServicePackageMemberRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageRequestDto;
import com.positivity.catalog.internal.dto.ServicePackageResponseDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.internal.service.ServicePackageService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Service packages and fleet requirement sets (#1575 Tier 0, T0-4). Durion-owned composition:
 * what a shop sells together, and what a fleet account requires on every visit.
 */
@Tag(
        name = "Service Packages",
        description = "Named sets of service operations sold together, and fleet accounts' requirement sets"
                + " (a package scoped to one fleet party).")
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping("/v1/service-packages")
public class ServicePackageController {

    private final ServicePackageService servicePackageService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_MANAGE})
    @PostMapping
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_CREATE", apiVersion = "1")
    @Operation(operationId = "createServicePackage", summary = "Create A Service Package", description = """
            Creates a named set of operations sold together — a four-tyre install with the balance and TPMS \
            reset, a fleet PM interval, a seasonal changeover — or, with fleetPartyId set, one fleet account's \
            requirement set.
            Use this tool to define what a shop offers as a unit; do not use it to add work to a workorder, \
            which is addEstimateItem.
            Preconditions: packageCode must be unique platform-wide. packageLaborHours is AUTHORED, not derived \
            from the members — the overlap arithmetic that turns member times into a total lives in the \
            workorder, and a shop prices a package as a number it chose.
            Required inputs: packageCode and name; ownerScope, ownerLocationId, fleetPartyId, \
            packageLaborHours, active and the effective window are optional.
            Emits a CATALOG_SERVICE_PACKAGE_CREATE event. The package is created empty; add operations with \
            addServicePackageMember.
            Returns 201 with the stored package, 409 when the code is taken, and 422 when a field is not \
            storable as described.
            """)
    @ApiResponse(responseCode = "201", description = "Service package created.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "409", description = "A package already exists with that code.")
    @ApiResponse(responseCode = "422", description = "The package cannot be stored as described.")
    public ResponseEntity<ServicePackageResponseDto> createServicePackage(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The package's identity, display name, ownership and authored hours."
                                    + " Set fleetPartyId to make it one account's requirement set.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ServicePackageRequestDto.class),
                                            examples =
                                                    @ExampleObject(
                                                            name = "Four tire installation package",
                                                            value = """
                                                            {"packageCode":"TIRE-INSTALL-PKG-4",
                                                             "name":"Four Tire Installation Package",
                                                             "packageLaborHours":1.6}
                                                            """)))
                    @Valid
                    @RequestBody
                    ServicePackageRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(servicePackageService.create(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_VIEW})
    @GetMapping
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_LIST", apiVersion = "1")
    @Operation(operationId = "listServicePackages", summary = "List Service Packages", description = """
            Lists the active packages a location may sell — its own plus every platform package, its own first \
            — each with its member operations in presentation order.
            Use this tool to show what can be added to a job as a unit, or with fleetPartyId to show what one \
            fleet account requires on every visit; do not use it to look up a single package you already have \
            the id for, which is getServicePackage.
            Preconditions: none. Fleet requirement sets are excluded from a general listing because they belong \
            to one account and are not on offer — naming a fleetPartyId includes them, as does \
            includeFleetPackages.
            Required inputs: none; locationId, fleetPartyId and includeFleetPackages are optional. Omitting \
            locationId lists platform packages only, which is the right answer for a caller quoting as the \
            platform.
            Emits a CATALOG_SERVICE_PACKAGE_LIST event; no state changes.
            Returns 200 with the packages, and an empty list when none match.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "The matching packages with their members.",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = ServicePackageResponseDto.class))))
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    public ResponseEntity<List<ServicePackageResponseDto>> listServicePackages(
            @Parameter(description = "Location whose packages to include alongside platform ones")
                    @RequestParam(required = false)
                    UUID locationId,
            @Parameter(description = "Narrow to one fleet account's requirement set") @RequestParam(required = false)
                    UUID fleetPartyId,
            @Parameter(description = "Include fleet requirement sets in a general listing; defaults to false")
                    @RequestParam(required = false, defaultValue = "false")
                    boolean includeFleetPackages) {
        return ResponseEntity.ok(servicePackageService.list(locationId, fleetPartyId, includeFleetPackages));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_VIEW})
    @GetMapping("/{packageId}")
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_GET", apiVersion = "1")
    @Operation(operationId = "getServicePackage", summary = "Get A Service Package", description = """
            Returns one package with its member operations in presentation order, including inactive packages.
            Use this tool when you already hold the package id; do not use it to discover what a location \
            sells, which is listServicePackages.
            Preconditions: the package must exist.
            Required inputs: packageId.
            Emits a CATALOG_SERVICE_PACKAGE_GET event; no state changes.
            Returns 200 with the package, and 404 when no package has that id.
            """)
    @ApiResponse(responseCode = "200", description = "The package with its members.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "No package has that id.")
    public ResponseEntity<ServicePackageResponseDto> getServicePackage(@PathVariable UUID packageId) {
        return ResponseEntity.ok(servicePackageService.get(packageId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_MANAGE})
    @PostMapping("/{packageId}/members")
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_MEMBER_ADD", apiVersion = "1")
    @Operation(operationId = "addServicePackageMember", summary = "Add An Operation To A Package", description = """
            Adds one catalog service operation to a package, required by default.
            Use this tool to compose a package; required=false marks an operation the package offers as an \
            upsell rather than includes by definition — which is what separates a fleet requirement from a \
            suggestion.
            Preconditions: the package and the service must both exist, and the service must not already be a \
            member. Wanting two of an operation is a quantity, not a second membership.
            Required inputs: serviceId; sequence, quantity and required are optional, and an omitted sequence \
            appends to the end.
            Emits a CATALOG_SERVICE_PACKAGE_MEMBER_ADD event.
            Returns 200 with the whole package including its updated member list, 404 when the package or \
            service does not exist, and 409 when the service is already a member.
            """)
    @ApiResponse(responseCode = "200", description = "The package with its updated members.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "The package or the service does not exist.")
    @ApiResponse(responseCode = "409", description = "That service is already a member.")
    public ResponseEntity<ServicePackageResponseDto> addServicePackageMember(
            @PathVariable UUID packageId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The operation to add, and whether the package includes it by"
                                    + " definition or merely offers it.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = ServicePackageMemberRequestDto.class),
                                            examples = @ExampleObject(name = "An included balance", value = """
                                                            {"serviceId":"99407ab3-901d-a7b6-816e-00bfb282ad4c",
                                                             "sequence":20,"quantity":1,"required":true}
                                                            """)))
                    @Valid
                    @RequestBody
                    ServicePackageMemberRequestDto request) {
        return ResponseEntity.ok(servicePackageService.addMember(packageId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SERVICE_PACKAGE_MANAGE + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SERVICE_PACKAGE_MANAGE})
    @DeleteMapping("/{packageId}/members/{memberId}")
    @EmitEvent(id = "CATALOG_SERVICE_PACKAGE_MEMBER_REMOVE", apiVersion = "1")
    @Operation(
            operationId = "removeServicePackageMember",
            summary = "Remove An Operation From A Package",
            description = """
            Removes one operation's membership of a package.
            Use this tool to correct a package's composition; do not use it to change how much of an \
            operation a package includes, which is a quantity on the membership rather than a removal. \
            Removing a member does not affect any workorder already quoted from the package, which \
            snapshots its own lines.
            Preconditions: the membership must exist and belong to the named package.
            Required inputs: packageId and memberId.
            Emits a CATALOG_SERVICE_PACKAGE_MEMBER_REMOVE event.
            Returns 200 with the whole package including its updated member list, and 404 when the membership \
            does not exist in that package.
            """)
    @ApiResponse(responseCode = "200", description = "The package with its updated members.")
    @ApiResponse(responseCode = "401", description = "Authentication required.")
    @ApiResponse(responseCode = "403", description = "Insufficient permissions.")
    @ApiResponse(responseCode = "404", description = "That membership does not exist in that package.")
    public ResponseEntity<ServicePackageResponseDto> removeServicePackageMember(
            @PathVariable UUID packageId, @PathVariable UUID memberId) {
        return ResponseEntity.ok(servicePackageService.removeMember(packageId, memberId));
    }
}
