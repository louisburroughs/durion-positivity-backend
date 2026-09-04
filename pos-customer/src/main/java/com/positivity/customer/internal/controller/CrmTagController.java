package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.PartyTagAssignmentResponse;
import com.positivity.customer.internal.dto.PartyTagResponse;
import com.positivity.customer.internal.dto.UpsertPartyTagRequest;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.internal.service.PartyTagService;
import com.positivity.events.EmitEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
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

    @Operation(operationId = "listTags", summary = "List Tag Catalog", description = """
                    Returns the CRM tag catalog with each tag's category, color, active flag, and \
                    assignment count.
                    Use this tool when browsing available tags before assigning one; use listPartyTags \
                    instead to see which tags a specific party carries.
                    Preconditions: none; inactive tags are omitted unless explicitly requested.
                    Required inputs: none; includeInactive defaults to false, and there is no request body.
                    Emits a CRM_TAG_LIST audit event; no state changes occur.
                    Returns 200 with an empty list rather than an error when the catalog is empty.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_VIEW + "')")
    @EmitEvent(id = "CRM_TAG_LIST", apiVersion = "1")
    public ResponseEntity<List<PartyTagResponse>> listTags(
            @RequestParam(name = "includeInactive", defaultValue = "false") boolean includeInactive) {
        return ResponseEntity.ok(partyTagService.listTags(includeInactive));
    }

    @Operation(operationId = "getTag", summary = "Get Tag By Id", description = """
                    Returns one catalog tag with its name, category, color, active flag, and assignment \
                    count.
                    Use this tool when the tag id is already known; use listTags instead to browse the \
                    catalog.
                    Preconditions: the tag must exist in the catalog.
                    Required inputs: tagId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_TAG_GET audit event; no state changes occur.
                    Returns 404 when no tag exists for the supplied tagId.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_VIEW + "')")
    @EmitEvent(id = "CRM_TAG_GET", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> getTag(@PathVariable UUID tagId) {
        return ResponseEntity.ok(partyTagService.getTag(tagId));
    }

    @Operation(operationId = "createTag", summary = "Create Catalog Tag", description = """
                    Adds a new tag to the CRM tag catalog for later assignment to parties.
                    Use this tool when a new label is needed; do not use assignTagToParty, which attaches an \
                    existing catalog tag to a party.
                    Preconditions: no existing tag may already use the same name case-insensitively.
                    Required inputs: name (non-blank, max 100); category, color, and active (default true) \
                    are optional.
                    Emits a CRM_TAG_CREATE event; the tag becomes immediately assignable when active.
                    Returns 409 when a tag with the same name already exists, and 400 when name is blank.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_MANAGE + "')")
    @EmitEvent(id = "CRM_TAG_CREATE", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> createTag(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The new tag's name and optional presentation attributes.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Fleet VIP tag", value = """
                                                                    {"name":"Fleet VIP",
                                                                     "category":"Loyalty",
                                                                     "color":"#D4AF37",
                                                                     "active":true}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpsertPartyTagRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(partyTagService.createTag(request));
    }

    @Operation(operationId = "updateTag", summary = "Update Catalog Tag", description = """
                    Renames, recolours, recategorizes, or retires a catalog tag; setting active false stops \
                    new assignments while keeping existing ones.
                    Use this tool when changing how a tag looks or retiring it; use deleteTag instead to \
                    remove the tag and every assignment of it.
                    Preconditions: the tag must exist, and the new name must not collide with another tag \
                    case-insensitively.
                    Required inputs: tagId (UUID) as a path parameter and name in the body; category, \
                    color, and active are optional.
                    Emits a CRM_TAG_UPDATE event; existing assignments are untouched.
                    Returns 404 when the tag does not exist, and 409 when the new name is already taken.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_MANAGE + "')")
    @EmitEvent(id = "CRM_TAG_UPDATE", apiVersion = "1")
    public ResponseEntity<PartyTagResponse> updateTag(
            @PathVariable UUID tagId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The revised tag attributes; omitted optional fields are cleared or left defaulted.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Retire tag", value = """
                                                                    {"name":"Fleet VIP",
                                                                     "category":"Loyalty",
                                                                     "active":false}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpsertPartyTagRequest request) {
        return ResponseEntity.ok(partyTagService.updateTag(tagId, request));
    }

    @Operation(operationId = "deleteTag", summary = "Delete Catalog Tag", description = """
                    Removes a tag from the catalog together with every assignment of it on every party.
                    Use this tool only when the tag and its history should disappear entirely; use \
                    updateTag with active false instead to retire a tag while keeping existing assignments.
                    Preconditions: the tag must exist; deletion is not reversible.
                    Required inputs: tagId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_TAG_DELETE event; all assignment rows for the tag are removed.
                    Returns 404 when no tag exists for the supplied tagId.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tag deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_MANAGE})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_MANAGE + "')")
    @EmitEvent(id = "CRM_TAG_DELETE", apiVersion = "1")
    public ResponseEntity<Void> deleteTag(@PathVariable UUID tagId) {
        partyTagService.deleteTag(tagId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listPartyTags", summary = "List Party Tag Assignments", description = """
                    Returns the tags currently attached to one party, with who assigned each tag, when, and \
                    through which source.
                    Use this tool when reviewing a party's labels; use listTags instead for the full \
                    catalog of assignable tags.
                    Preconditions: none; an unknown partyId yields an empty list rather than an error.
                    Required inputs: partyId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_PARTY_TAG_LIST audit event; no state changes occur.
                    Returns 200 with an empty list rather than an error when the party has no tags.
                    """)
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
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_VIEW + "')")
    @EmitEvent(id = "CRM_PARTY_TAG_LIST", apiVersion = "1")
    public ResponseEntity<List<PartyTagAssignmentResponse>> listPartyTags(@PathVariable UUID partyId) {
        return ResponseEntity.ok(partyTagService.listPartyTags(partyId));
    }

    @Operation(operationId = "assignTagToParty", summary = "Assign Tag To Party", description = """
                    Attaches a catalog tag to a party, recording the assigning user, timestamp, and source.
                    Use this tool when labeling a party; do not use createTag, which adds a new tag to the \
                    catalog without attaching it to anyone.
                    Preconditions: the tag must exist and, for a new assignment, be active; the call is \
                    idempotent, and re-assigning an already-attached tag returns the existing assignment \
                    even when the tag has since been retired.
                    Required inputs: partyId (UUID) as a path parameter and tagId (UUID) in the body; \
                    source defaults to MANUAL and accepts MANUAL, CAMPAIGN, IMPORT, or RULE.
                    Emits a CRM_PARTY_TAG_ASSIGN event and publishes a party-tag-changed fact when a new \
                    assignment is created.
                    Returns 404 when the tag does not exist, and 422 when the tag is inactive and not \
                    already assigned.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Tag assigned (or already present)",
                content = @Content(schema = @Schema(implementation = PartyTagAssignmentResponse.class))),
        @ApiResponse(responseCode = "422", description = "Tag is inactive", content = @Content),
        @ApiResponse(responseCode = "404", description = "Tag not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/parties/{partyId}/tags")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_ASSIGN})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_ASSIGN + "')")
    @EmitEvent(id = "CRM_PARTY_TAG_ASSIGN", apiVersion = "1")
    public ResponseEntity<PartyTagAssignmentResponse> assignTag(
            @PathVariable UUID partyId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The catalog tag to attach to the party and how the assignment originated.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Manual assignment", value = """
                                                                    {"tagId":"018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a61",
                                                                     "source":"MANUAL"}
                                                                    """)))
                    @Valid
                    @RequestBody
                    AssignPartyTagRequest request) {
        return ResponseEntity.ok(partyTagService.assignTag(partyId, request));
    }

    @Operation(operationId = "removeTagFromParty", summary = "Remove Tag From Party", description = """
                    Detaches a tag from a party while leaving the tag itself in the catalog.
                    Use this tool when a label no longer applies to a party; use deleteTag instead to \
                    remove the tag from the catalog and every party at once.
                    Preconditions: none; removing a tag that is not assigned is an idempotent no-op.
                    Required inputs: partyId and tagId (UUIDs) as path parameters; there is no request body.
                    Emits a CRM_PARTY_TAG_REMOVE event and publishes a party-tag-changed fact when an \
                    assignment was actually removed.
                    Returns 204 in every authorized call, including when nothing was assigned.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Tag removed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/parties/{partyId}/tags/{tagId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.TAG_ASSIGN})
    @PreAuthorize("hasAuthority('" + CrmPermissionRegistry.TAG_ASSIGN + "')")
    @EmitEvent(id = "CRM_PARTY_TAG_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeTag(@PathVariable UUID partyId, @PathVariable UUID tagId) {
        partyTagService.removeTag(partyId, tagId);
        return ResponseEntity.noContent().build();
    }
}
