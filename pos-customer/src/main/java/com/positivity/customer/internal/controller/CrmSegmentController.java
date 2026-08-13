package com.positivity.customer.internal.controller;

import com.positivity.customer.internal.dto.SegmentAttributeResponse;
import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.SegmentResolutionResponse;
import com.positivity.customer.internal.dto.SegmentResponse;
import com.positivity.customer.internal.dto.UpsertSegmentRequest;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.security.CrmPermissionRegistry;
import com.positivity.customer.service.SegmentService;
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
 * Saved audience segments (Story #1137).
 *
 * <p>Resolution returns counts and a masked sample only — the full recipient list stays
 * in-process so audience preview cannot become a bulk PII export.
 */
@Tag(name = "CRM Segments", description = "Saved audience segments with static lists or validated predicates")
@RestController
@RequestMapping("/v1/crm/segments")
public class CrmSegmentController {

    private final SegmentService segmentService;

    public CrmSegmentController(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @Operation(operationId = "listSegments", summary = "List Audience Segments", description = """
                    Returns all saved audience segments with their type, audience type, active flag, and \
                    member counts, optionally filtered by audience type.
                    Use this tool when browsing or picking a segment; use getSegment instead when the \
                    segment id is already known.
                    Preconditions: none; an empty list is returned when no segment matches.
                    Required inputs: none; audienceType optionally filters on COMMERCIAL or INDIVIDUAL, and \
                    there is no request body.
                    Emits a CRM_SEGMENT_LIST audit event; no state changes occur.
                    Returns 200 with an empty list rather than an error when no segments exist.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Segments returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array = @ArraySchema(schema = @Schema(implementation = SegmentResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_VIEW})
    @PreAuthorize("hasAuthority('crm:segment:view')")
    @EmitEvent(id = "CRM_SEGMENT_LIST", apiVersion = "1")
    public ResponseEntity<List<SegmentResponse>> list(
            @RequestParam(name = "audienceType", required = false) AudienceType audienceType) {
        return ResponseEntity.ok(segmentService.list(audienceType));
    }

    @Operation(operationId = "listSegmentAttributes", summary = "List Segment Attribute Catalog", description = """
                    Returns the whitelist of attributes a dynamic segment predicate may reference, each with \
                    its operand kind, allowed operators, and description.
                    Use this tool before createSegment or updateSegment to build a valid DYNAMIC predicate; \
                    do not use listSegments, which lists saved segments rather than the predicate vocabulary.
                    Preconditions: none; the catalog is fixed in code per audience type.
                    Required inputs: none; there are no parameters and no request body.
                    Emits a CRM_SEGMENT_ATTRIBUTES audit event; no state changes occur.
                    Returns 200 with the full catalog in every successful call; there are no business error \
                    responses.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Attribute catalog returned",
                content =
                        @Content(
                                mediaType = "application/json",
                                array =
                                        @ArraySchema(
                                                schema = @Schema(implementation = SegmentAttributeResponse.class)))),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/attributes")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_VIEW})
    @PreAuthorize("hasAuthority('crm:segment:view')")
    @EmitEvent(id = "CRM_SEGMENT_ATTRIBUTES", apiVersion = "1")
    public ResponseEntity<List<SegmentAttributeResponse>> attributeCatalog() {
        return ResponseEntity.ok(segmentService.attributeCatalog());
    }

    @Operation(operationId = "getSegment", summary = "Get Segment Definition", description = """
                    Returns one segment's definition, including its type, audience type, predicate for \
                    DYNAMIC segments, active flag, and member count.
                    Use this tool when the segment id is already known; use listSegments instead to browse \
                    segments, and resolveSegment to compute who currently matches.
                    Preconditions: the segment must exist.
                    Required inputs: segmentId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_SEGMENT_GET audit event; no state changes occur.
                    Returns 404 when no segment exists for the supplied segmentId.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Segment returned",
                content = @Content(schema = @Schema(implementation = SegmentResponse.class))),
        @ApiResponse(responseCode = "404", description = "Segment not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @GetMapping("/{segmentId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_VIEW})
    @PreAuthorize("hasAuthority('crm:segment:view')")
    @EmitEvent(id = "CRM_SEGMENT_GET", apiVersion = "1")
    public ResponseEntity<SegmentResponse> get(@PathVariable UUID segmentId) {
        return ResponseEntity.ok(segmentService.get(segmentId));
    }

    @Operation(operationId = "createSegment", summary = "Create Audience Segment", description = """
                    Creates a saved audience segment, either STATIC with an explicitly pinned member list or \
                    DYNAMIC with a predicate validated against the attribute catalog.
                    Use this tool when defining a new audience; do not use updateSegment, which modifies an \
                    existing segment and cannot change its type or audience type.
                    Preconditions: no existing segment may already use the same name case-insensitively; a \
                    DYNAMIC segment must carry a predicate and a STATIC one must not.
                    Required inputs: name (max 150), audienceType (COMMERCIAL or INDIVIDUAL), and type \
                    (STATIC or DYNAMIC); description, predicate, and active are optional.
                    Emits a CRM_SEGMENT_CREATE event and publishes the segment definition to consumers.
                    Returns 409 when the name is already taken, and 422 when the predicate is missing on a \
                    DYNAMIC segment, present on a STATIC one, or references attributes outside the catalog.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "201",
                description = "Segment created",
                content = @Content(schema = @Schema(implementation = SegmentResponse.class))),
        @ApiResponse(responseCode = "409", description = "Segment name already exists", content = @Content),
        @ApiResponse(responseCode = "422", description = "Predicate is not valid for this catalog", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_MANAGE})
    @PreAuthorize("hasAuthority('crm:segment:manage')")
    @EmitEvent(id = "CRM_SEGMENT_CREATE", apiVersion = "1")
    public ResponseEntity<SegmentResponse> create(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The segment definition; DYNAMIC segments carry a predicate tree over catalog attributes, STATIC ones do not.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Dynamic gold-tier segment", value = """
                                                                    {"name":"Gold-tier commercial accounts",
                                                                     "description":"Commercial parties at GOLD tier or above",
                                                                     "audienceType":"COMMERCIAL",
                                                                     "type":"DYNAMIC",
                                                                     "predicate":{"nodeType":"COMPARISON",
                                                                                  "attribute":"party.accountTier",
                                                                                  "operator":"IN",
                                                                                  "values":["GOLD","PLATINUM","ENTERPRISE"]}}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpsertSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segmentService.create(request));
    }

    @Operation(operationId = "updateSegment", summary = "Update Audience Segment", description = """
                    Updates a segment's name, description, predicate, or active flag; type and audienceType \
                    are immutable after creation because switching kinds would strand pinned members or a \
                    predicate.
                    Use this tool when refining an existing segment; use createSegment instead when a \
                    different type or audience type is needed.
                    Preconditions: the segment must exist, the new name must not collide with another \
                    segment case-insensitively, and the submitted type and audienceType must equal the \
                    stored values.
                    Required inputs: segmentId (UUID) as a path parameter plus the full upsert body with \
                    name, audienceType, and type restated; predicate rules follow the segment's type and \
                    active is optional.
                    Emits a CRM_SEGMENT_UPDATE event and republishes the segment definition.
                    Returns 404 when the segment does not exist, 409 when the name is taken, and 422 when \
                    type or audienceType differ from the stored segment or the predicate is invalid.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Segment updated",
                content = @Content(schema = @Schema(implementation = SegmentResponse.class))),
        @ApiResponse(responseCode = "404", description = "Segment not found", content = @Content),
        @ApiResponse(responseCode = "409", description = "Segment name already exists", content = @Content),
        @ApiResponse(
                responseCode = "422",
                description = "Predicate invalid, or an immutable field was changed",
                content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PutMapping("/{segmentId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_MANAGE})
    @PreAuthorize("hasAuthority('crm:segment:manage')")
    @EmitEvent(id = "CRM_SEGMENT_UPDATE", apiVersion = "1")
    public ResponseEntity<SegmentResponse> update(
            @PathVariable UUID segmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description =
                                    "The revised segment definition; type and audienceType must match the stored segment.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Rename and deactivate", value = """
                                                                    {"name":"Gold-tier accounts (retired)",
                                                                     "audienceType":"COMMERCIAL",
                                                                     "type":"DYNAMIC",
                                                                     "predicate":{"nodeType":"COMPARISON",
                                                                                  "attribute":"party.accountTier",
                                                                                  "operator":"IN",
                                                                                  "values":["GOLD"]},
                                                                     "active":false}
                                                                    """)))
                    @Valid
                    @RequestBody
                    UpsertSegmentRequest request) {
        return ResponseEntity.ok(segmentService.update(segmentId, request));
    }

    @Operation(operationId = "deleteSegment", summary = "Delete Audience Segment", description = """
                    Deletes a segment and all of its pinned membership rows permanently.
                    Use this tool when a segment is no longer wanted at all; use updateSegment with active \
                    false instead to retire a segment while keeping its definition.
                    Preconditions: the segment must exist; deletion is not reversible.
                    Required inputs: segmentId (UUID) as a path parameter; there is no request body.
                    Emits a CRM_SEGMENT_DELETE event and publishes a deletion notice so consumers drop the \
                    segment.
                    Returns 404 when no segment exists for the supplied segmentId.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Segment deleted", content = @Content),
        @ApiResponse(responseCode = "404", description = "Segment not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/{segmentId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_MANAGE})
    @PreAuthorize("hasAuthority('crm:segment:manage')")
    @EmitEvent(id = "CRM_SEGMENT_DELETE", apiVersion = "1")
    public ResponseEntity<Void> delete(@PathVariable UUID segmentId) {
        segmentService.delete(segmentId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "addSegmentMembers", summary = "Pin Segment Members", description = """
                    Pins parties onto a STATIC segment's member list, recording who added them and when; \
                    parties already in the segment are silently skipped.
                    Use this tool when curating a hand-picked audience; do not use it on a DYNAMIC segment, \
                    whose membership is computed from its predicate by resolveSegment.
                    Preconditions: the segment must exist and be STATIC.
                    Required inputs: segmentId (UUID) as a path parameter and partyIds, a non-empty list of \
                    up to 5000 UUIDs; duplicates in the list are collapsed.
                    Emits a CRM_SEGMENT_MEMBERS_ADD event; only new membership rows are written.
                    Returns 404 when the segment does not exist, and 422 when the segment is DYNAMIC.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Members added",
                content = @Content(schema = @Schema(implementation = SegmentResponse.class))),
        @ApiResponse(responseCode = "404", description = "Segment not found", content = @Content),
        @ApiResponse(responseCode = "422", description = "Segment is dynamic", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{segmentId}/members")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_MANAGE})
    @PreAuthorize("hasAuthority('crm:segment:manage')")
    @EmitEvent(id = "CRM_SEGMENT_MEMBERS_ADD", apiVersion = "1")
    public ResponseEntity<SegmentResponse> addMembers(
            @PathVariable UUID segmentId,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "The party ids to pin onto the static segment's member list.",
                            required = true,
                            content =
                                    @Content(
                                            mediaType = "application/json",
                                            examples = @ExampleObject(name = "Pin two parties", value = """
                                                                    {"partyIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b",
                                                                                 "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    SegmentMembersRequest request) {
        return ResponseEntity.ok(segmentService.addMembers(segmentId, request));
    }

    @Operation(operationId = "removeSegmentMember", summary = "Unpin Segment Member", description = """
                    Removes one party from a static segment's pinned member list.
                    Use this tool when a hand-picked party should leave the audience; do not use \
                    deleteSegment, which removes the whole segment and every member with it.
                    Preconditions: none are enforced; removing a party that is not a member, or naming an \
                    unknown segment, is a silent no-op.
                    Required inputs: segmentId and partyId (UUIDs) as path parameters; there is no request \
                    body.
                    Emits a CRM_SEGMENT_MEMBER_REMOVE event; at most one membership row is deleted.
                    Returns 204 in every authorized call, including when nothing was actually removed.
                    """)
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Member removed", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @DeleteMapping("/{segmentId}/members/{partyId}")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_MANAGE})
    @PreAuthorize("hasAuthority('crm:segment:manage')")
    @EmitEvent(id = "CRM_SEGMENT_MEMBER_REMOVE", apiVersion = "1")
    public ResponseEntity<Void> removeMember(@PathVariable UUID segmentId, @PathVariable UUID partyId) {
        segmentService.removeMember(segmentId, partyId);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "resolveSegment", summary = "Resolve Segment Audience", description = """
                    Resolves a segment to its current match count, an optional per-channel eligible count \
                    that folds in consent and suppression, and a masked sample of members; the full \
                    recipient list is never returned, so the preview cannot become a bulk PII export.
                    Use this tool when previewing an audience before a campaign; use getSegment instead for \
                    the stored definition without computing membership.
                    Preconditions: the segment must exist; eligible counts are computed only when a channel \
                    is supplied.
                    Required inputs: segmentId (UUID) as a path parameter; channel (EMAIL or SMS) is \
                    optional, and sampleSize defaults to 10 and is clamped to the server's maximum.
                    Emits a CRM_SEGMENT_RESOLVE audit event; no state changes occur.
                    Returns 404 when no segment exists for the supplied segmentId, and 200 with a truncated \
                    flag when the match list was capped during resolution.
                    """)
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "Resolution returned",
                content = @Content(schema = @Schema(implementation = SegmentResolutionResponse.class))),
        @ApiResponse(responseCode = "404", description = "Segment not found", content = @Content),
        @ApiResponse(responseCode = "403", description = "Forbidden - insufficient permissions", content = @Content)
    })
    @PostMapping("/{segmentId}/resolve")
    @SecurityRequirement(
            name = "bearerAuth",
            scopes = {CrmPermissionRegistry.SEGMENT_RESOLVE})
    @PreAuthorize("hasAuthority('crm:segment:resolve')")
    @EmitEvent(id = "CRM_SEGMENT_RESOLVE", apiVersion = "1")
    public ResponseEntity<SegmentResolutionResponse> resolve(
            @PathVariable UUID segmentId,
            @RequestParam(name = "channel", required = false) MarketingChannel channel,
            @RequestParam(name = "sampleSize", defaultValue = "10") int sampleSize) {
        return ResponseEntity.ok(segmentService.resolve(segmentId, channel, sampleSize));
    }
}
