package com.positivity.catalog.internal.controller;

import com.positivity.catalog.internal.dto.SubstitutionGroupCreateRequestDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupDto;
import com.positivity.catalog.internal.dto.SubstitutionGroupMemberRequestDto;
import com.positivity.catalog.service.SubstitutionGroupService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
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

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping
    @Operation(
            summary = "Create substitution group",
            description = "Creates an empty substitution group; add members afterwards.",
            operationId = "createSubstitutionGroup")
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
            @Valid @RequestBody SubstitutionGroupCreateRequestDto request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(substitutionGroupService.createGroup(request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping
    @Operation(
            summary = "List substitution groups",
            description = "Returns all substitution groups with their member product ids.",
            operationId = "listSubstitutionGroups")
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

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_VIEW')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_VIEW"})
    @GetMapping("/{groupId}")
    @Operation(
            summary = "Get substitution group",
            description = "Retrieves a substitution group with its member product ids.",
            operationId = "getSubstitutionGroup")
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

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @DeleteMapping("/{groupId}")
    @Operation(
            summary = "Delete substitution group",
            description = "Deletes a group and its memberships, re-emitting the product contract event"
                    + " for every former member.",
            operationId = "deleteSubstitutionGroup")
    @ApiResponse(responseCode = "204", description = "Substitution group deleted")
    @ApiResponse(responseCode = "404", description = "Substitution group not found")
    @EmitEvent(id = "CATALOG_SUBSTITUTION_GROUP_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteGroup(
            @Parameter(description = "Substitution group ID", required = true) @PathVariable UUID groupId) {
        substitutionGroupService.deleteGroup(groupId);
        return ResponseEntity.noContent().build();
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @PostMapping("/{groupId}/members")
    @Operation(
            summary = "Add product to substitution group",
            description = "Adds a product to the group (a product belongs to at most one group) and re-emits the"
                    + " product contract event for every member.",
            operationId = "addSubstitutionGroupMember")
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
            @Valid @RequestBody SubstitutionGroupMemberRequestDto request) {
        return ResponseEntity.ok(substitutionGroupService.addMember(groupId, request));
    }

    @PreAuthorize("hasRole('ADMIN') or hasRole('CATALOG_EDIT')")
    @io.swagger.v3.oas.annotations.security.SecurityRequirement(
            name = "bearerAuth",
            scopes = {"ROLE_ADMIN", "ROLE_CATALOG_EDIT"})
    @DeleteMapping("/{groupId}/members/{productId}")
    @Operation(
            summary = "Remove product from substitution group",
            description = "Removes a product from the group and re-emits the product contract event for the removed"
                    + " product and every remaining member.",
            operationId = "removeSubstitutionGroupMember")
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
