package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SubstitutionGroupCreateRequestDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupMemberRequestDto;
import com.positivity.catalog.internal.security.CatalogPermissions;
import com.positivity.catalog.service.SubstitutionGroupService;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/v1/products/substitution-groups")
@Tag(
        name = "Substitution Group API",
        description = "Groups of mutually interchangeable products; a product belongs to at most one group")
public class SubstitutionGroupController {

    private final SubstitutionGroupService substitutionGroupService;

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_EDIT})
    @PostMapping
    @Operation(operationId = "createSubstitutionGroup", summary = "Create Substitution Group", description = """
            Creates an empty, named substitution group — a set of mutually interchangeable products in which \
            each product may belong to at most one group.
            Use this tool to establish the group before populating it; do not use \
            addSubstitutionGroupMember, which adds products to a group that already exists, and do not \
            confuse groups with addProductReplacement, which records one-way successors for discontinued \
            products.
            Preconditions: none; group names are not checked for uniqueness.
            Required inputs: name (non-blank, trimmed on save); notes are optional.
            Emits a CATALOG_SUBSTITUTION_GROUP_CREATE event; no product facts are re-published until members \
            are added.
            Returns 201 with the group and an empty productIds list, and 400 when name is missing or blank.
            """)
    @ApiResponse(
            responseCode = "201",
            description = "Substitution group created",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SubstitutionGroupDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @EmitEvent(id = "CATALOG_SUBSTITUTION_GROUP_CREATE", apiVersion = "1")
    public ResponseEntity<SubstitutionGroupDto> createGroup(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Name and optional notes for the new interchangeability group.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = SubstitutionGroupCreateRequestDto.class),
                                            examples = @ExampleObject(name = "Oil filter group", value = """
                                                                    {"name":"Oil Filters PH3593A-Compatible",
                                                                     "notes":"Cross-brand equivalents for PH3593A fitment"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubstitutionGroupCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(substitutionGroupService.createGroup(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_VIEW})
    @GetMapping
    @Operation(operationId = "listSubstitutionGroups", summary = "List Substitution Groups", description = """
            Returns every substitution group with its name, notes and member product ids ordered by when \
            each member joined.
            Use this tool to discover a groupId or survey interchangeability sets; use getSubstitutionGroup \
            instead when the id is already known.
            Preconditions: none; the list is unfiltered and unpaged.
            Required inputs: none, and there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 200 with an empty array when no groups exist, so an empty result is not an error \
            condition.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Substitution groups listed",
            content =
                    @Content(
                            mediaType = "application/json",
                            array = @ArraySchema(schema = @Schema(implementation = SubstitutionGroupDto.class))))
    public ResponseEntity<List<SubstitutionGroupDto>> listGroups() {
        return ResponseEntity.ok(substitutionGroupService.listGroups());
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_VIEW + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_VIEW})
    @GetMapping("/{groupId}")
    @Operation(operationId = "getSubstitutionGroup", summary = "Get Substitution Group", description = """
            Returns one substitution group with its name, notes and member product ids ordered by when each \
            member joined.
            Use this tool when the groupId is already known; use listSubstitutionGroups instead to discover \
            groups.
            Preconditions: the group must exist.
            Required inputs: groupId (UUID) as a path parameter; there is no request body.
            No events are emitted and no state changes; this is a read-only projection.
            Returns 404 when no substitution group exists for the supplied id.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Substitution group found",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SubstitutionGroupDto.class)))
    @ApiResponse(responseCode = "404", description = "Substitution group not found")
    public ResponseEntity<SubstitutionGroupDto> getGroup(
            @Parameter(description = "Substitution group ID", required = true) @PathVariable UUID groupId) {
        return ResponseEntity.ok(substitutionGroupService.getGroup(groupId));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_EDIT})
    @DeleteMapping("/{groupId}")
    @Operation(operationId = "deleteSubstitutionGroup", summary = "Delete Substitution Group", description = """
            Deletes a substitution group and all of its memberships permanently, freeing every former member \
            to join another group; the products themselves are untouched.
            Use this tool to dissolve a whole group; use removeSubstitutionGroupMember instead to take a \
            single product out while keeping the group.
            Preconditions: the group must exist; there is no soft delete or archive.
            Required inputs: groupId (UUID) as a path parameter; there is no request body.
            Emits a CATALOG_SUBSTITUTION_GROUP_DELETE event and re-publishes the product fact for every \
            former member so downstream replicas drop the association.
            Returns 204 on success, and 404 when no substitution group exists for the supplied id.
            """)
    @ApiResponse(responseCode = "204", description = "Substitution group deleted")
    @ApiResponse(responseCode = "404", description = "Substitution group not found")
    @EmitEvent(id = "CATALOG_SUBSTITUTION_GROUP_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "Substitution group ID", required = true) @PathVariable UUID groupId) {
        substitutionGroupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_EDIT})
    @PostMapping("/{groupId}/members")
    @Operation(operationId = "addSubstitutionGroupMember", summary = "Add Substitution Group Member", description = """
            Adds a product to a substitution group, making it mutually interchangeable with every other \
            member; membership is exclusive, a product can belong to only one group at a time.
            Use this tool to grow an interchangeability set; do not use addProductReplacement, which records \
            a one-way successor for a discontinued product rather than a symmetric substitute.
            Preconditions: the group and the product must both exist, and the product must not already \
            belong to any substitution group, including this one.
            Required inputs: groupId (UUID) path parameter and productId (UUID) in the body.
            Emits a CATALOG_SUBSTITUTION_GROUP_MEMBER_ADD event and re-publishes the product fact for every \
            member of the group so downstream replicas learn the new association.
            Returns 404 when the group or the product does not exist, and 409 when the product already \
            belongs to a substitution group.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Member added",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SubstitutionGroupDto.class)))
    @ApiResponse(responseCode = "400", description = "Validation error")
    @ApiResponse(responseCode = "404", description = "Substitution group or product not found")
    @ApiResponse(responseCode = "409", description = "Product already belongs to a substitution group")
    @EmitEvent(id = "CATALOG_SUBSTITUTION_GROUP_MEMBER_ADD", apiVersion = "1")
    public ResponseEntity<SubstitutionGroupDto> addMember(
            @Parameter(description = "Substitution group ID", required = true) @PathVariable UUID groupId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The product to add as an interchangeable member of the group.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            schema = @Schema(implementation = SubstitutionGroupMemberRequestDto.class),
                                            examples = @ExampleObject(name = "Add product", value = """
                                                                    {"productId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SubstitutionGroupMemberRequestDto request) {
        return ResponseEntity.ok(substitutionGroupService.addMember(groupId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasAuthority('" + CatalogPermissions.SUBSTITUTION_GROUP_EDIT + "')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", CatalogPermissions.SUBSTITUTION_GROUP_EDIT})
    @DeleteMapping("/{groupId}/members/{productId}")
    @Operation(
            operationId = "removeSubstitutionGroupMember",
            summary = "Remove Substitution Group Member",
            description = """
            Removes a product from a substitution group, ending its interchangeability with the remaining \
            members and freeing it to join another group.
            Use this tool to take one product out; use deleteSubstitutionGroup instead to dissolve the \
            entire group at once.
            Preconditions: the group must exist and the product must currently be a member of that specific \
            group.
            Required inputs: groupId and productId (UUIDs) as path parameters; there is no request body.
            Emits a CATALOG_SUBSTITUTION_GROUP_MEMBER_REMOVE event and re-publishes the product fact for the \
            removed product and every remaining member.
            Returns 200 with the group's remaining membership, and 404 when the group does not exist or the \
            product is not a member of it.
            """)
    @ApiResponse(
            responseCode = "200",
            description = "Member removed",
            content =
                    @Content(
                            mediaType = "application/json",
                            schema = @Schema(implementation = SubstitutionGroupDto.class)))
    @ApiResponse(responseCode = "404", description = "Substitution group or membership not found")
    @EmitEvent(id = "CATALOG_SUBSTITUTION_GROUP_MEMBER_REMOVE", apiVersion = "1")
    public ResponseEntity<SubstitutionGroupDto> removeMember(
            @Parameter(description = "Substitution group ID", required = true) @PathVariable UUID groupId,
            @Parameter(description = "Product ID to remove", required = true) @PathVariable UUID productId) {
        return ResponseEntity.ok(substitutionGroupService.removeMember(groupId, productId));
    }
}
