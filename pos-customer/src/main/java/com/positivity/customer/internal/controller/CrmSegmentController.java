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

    @Operation(summary = "List segments", description = "List saved segments, optionally filtered by audience type")
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

    @Operation(
            summary = "List the segment attribute catalog",
            description =
                    "Every whitelisted attribute a dynamic predicate may reference, with operand kind, allowed operators, and description")
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

    @Operation(summary = "Get segment", description = "Retrieve a segment definition by id")
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

    @Operation(
            summary = "Create segment",
            description =
                    "Create a static or dynamic segment. Dynamic predicates are validated against the attribute catalog.")
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
    public ResponseEntity<SegmentResponse> create(@Valid @RequestBody UpsertSegmentRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(segmentService.create(request));
    }

    @Operation(
            summary = "Update segment",
            description =
                    "Update a segment's name, description, predicate, or active flag. Type and audience type are immutable.")
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
            @PathVariable UUID segmentId, @Valid @RequestBody UpsertSegmentRequest request) {
        return ResponseEntity.ok(segmentService.update(segmentId, request));
    }

    @Operation(summary = "Delete segment", description = "Delete a segment and its pinned membership")
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

    @Operation(
            summary = "Pin members",
            description = "Add parties to a static segment; already-present parties are ignored")
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
            @PathVariable UUID segmentId, @Valid @RequestBody SegmentMembersRequest request) {
        return ResponseEntity.ok(segmentService.addMembers(segmentId, request));
    }

    @Operation(summary = "Unpin member", description = "Remove a party from a static segment")
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

    @Operation(
            summary = "Resolve segment",
            description =
                    "Resolve a segment to a match count, an optional per-channel eligible count, and a masked sample")
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
