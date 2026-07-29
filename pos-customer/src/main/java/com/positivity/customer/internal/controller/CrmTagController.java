package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.PartyTagAssignmentResponse;
import com.positivity.customer.internal.dto.PartyTagResponse;
import com.positivity.customer.internal.dto.UpsertPartyTagRequest;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.PartyTagService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

/**
 * CRM tag catalog and per-party tag assignment (Story #1136).
 */
@Tag(name = "CRM Tags", description = "Manage the CRM tag catalog and attach tags to parties")
@RestController
@RequestMapping("/v1/crm")
public class CrmTagController {

    private final PartyTagService partyTagService;

    public CrmTagController(PartyTagService partyTagService) {
        this.partyTagService = partyTagService;
    }

    @Operation(summary = "List tags", description = "List the CRM tag catalog with assignment counts")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tags returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array = @ArraySchema(schema = @Schema(implementation = PartyTagResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/tags")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_VIEW})
    @PreAuthorize("hasAuthority('crm:tag:view')")
    @EmitEvent(id = "CRM_TAG_LIST", apiVersion = "1")
    public ResponseEntity<List<PartyTagResponse>> listTags(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(partyTagService.listTags(includeInactive));
    }

    @Operation(summary = "Get tag", description = "Retrieve a single tag by id")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tag returned",
                content = @Content(schema = @Schema(implementation = PartyTagResponse.class))),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_VIEW})
    @PreAuthorize("hasAuthority('crm:tag:view')")
    @EmitEvent(id = "CRM_TAG_GET", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> getTag(@PathVariable UUID tagId) {
        return ResponseEntity.ok(partyTagService.getTag(tagId));
    }

    @Operation(summary = "Create tag", description = "Add a new tag to the CRM tag catalog")
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Tag created",
                content = @Content(schema = @Schema(implementation = PartyTagResponse.class))),
        @ApiResponse(responseCode = "409", description = "Tag name already exists", content = @Content),
        @ApiResponse(responseCode = "400", description = "Validation failed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/tags")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_MANAGE})
    @PreAuthorize("hasAuthority('crm:tag:manage')")
    @EmitEvent(id = "CRM_TAG_CREATE", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> createTag(@Valid @RequestBody UpsertPartyTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partyTagService.createTag(request));
    }

    @Operation(summary = "Update tag", description = "Rename, recolour, recategorize, or retire a tag")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tag updated",
                content = @Content(schema = @Schema(implementation = PartyTagResponse.class))),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Tag name already exists", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_MANAGE})
    @PreAuthorize("hasAuthority('crm:tag:manage')")
    @EmitEvent(id = "CRM_TAG_UPDATE", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> updateTag(
            @PathVariable UUID tagId, @Valid @RequestBody UpsertPartyTagRequest request) {
        return ResponseEntity.ok(partyTagService.updateTag(tagId, request));
    }

    @Operation(summary = "Delete tag", description = "Remove a tag from the catalog along with every assignment of it")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tag deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_MANAGE})
    @PreAuthorize("hasAuthority('crm:tag:manage')")
    @EmitEvent(id = "CRM_TAG_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteTag(@PathVariable UUID tagId) {
        partyTagService.deleteTag(tagId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "List party tags", description = "List the tags currently attached to a party")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Party tags returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array =
                                        @ArraySchema(
                                                schema = @Schema(implementation = PartyTagAssignmentResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/parties/{partyId}/tags")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_VIEW})
    @PreAuthorize("hasAuthority('crm:tag:view')")
    @EmitEvent(id = "CRM_PARTY_TAG_LIST", apiVersion = "1")
    public ResponseEntity<List<PartyTagAssignmentResponse>> listPartyTags(@PathVariable UUID partyId) {
        return ResponseEntity.ok(partyTagService.listPartyTags(partyId));
    }

    @Operation(
            summary = "Assign tag to party",
            description = "Attach a tag to a party. Idempotent: re-assigning returns the existing assignment.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tag assigned (or already present)",
                content = @Content(schema = @Schema(implementation = PartyTagAssignmentResponse.class))),
        @ApiResponse(responseCode = "400", description = "Tag is inactive", content = @Content),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/parties/{partyId}/tags")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_ASSIGN})
    @PreAuthorize("hasAuthority('crm:tag:assign')")
    @EmitEvent(id = "CRM_PARTY_TAG_ASSIGN", apiVersion = "1")
    public ResponseEntity<PartyTagAssignmentResponse> assignTag(
            @PathVariable UUID partyId, @Valid @RequestBody AssignPartyTagRequest request) {
        return ResponseEntity.ok(partyTagService.assignTag(partyId, request));
    }

    @Operation(
            summary = "Remove tag from party",
            description = "Detach a tag from a party. Idempotent: removing an absent tag succeeds.")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tag removed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/parties/{partyId}/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_ASSIGN})
    @PreAuthorize("hasAuthority('crm:tag:assign')")
    @EmitEvent(id = "CRM_PARTY_TAG_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeTag(@PathVariable UUID partyId, @PathVariable UUID tagId) {
        partyTagService.removeTag(partyId, tagId);
        return ResponseEntity.noContent().build();
    }
}
