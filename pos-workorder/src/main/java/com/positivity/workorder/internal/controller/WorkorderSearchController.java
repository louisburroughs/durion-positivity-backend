package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderNumberResolveRequest;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.service.WorkorderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"workorder:workorder:view"})
@RequestMapping("/v1/workorders")
@Tag(name = "Workorder Search", description = "Workorder search and retrieval")
@RequiredArgsConstructor
public class WorkorderSearchController {

    private final WorkorderSearchService workorderSearchService;

    @Operation(operationId = "searchWorkorders", summary = "Search Workorders With Filters", description = """
                    Searches workorders by free-text query against customer display names or a literal workorder \
                    id, optionally narrowed by exact customerId and vehicleId filters, returning a page of rows \
                    enriched with customer name, vehicle label, and VIN.
                    Use this tool when finding workorders by customer or id fragments; use listWorkorders instead \
                    for an unfiltered listing, and resolveWorkorderNumbers to map known ids to human numbers.
                    Preconditions: customer-name matching depends on the local customer replica; at most 10 \
                    name-matched customers are considered per query.
                    Required inputs: none are mandatory — q defaults to an empty string and is treated as a \
                    workorder id when it parses as a UUID; page size defaults to 25.
                    Emits a WORKORDER_SEARCH audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 200 with an empty page when nothing matches; no 404 is produced for empty results.
                    """)
    @ApiResponse(responseCode = "200", description = "Page of workorder search results returned.")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    @EmitEvent(id = "WORKORDER_SEARCH", apiVersion = "1")
    public Page<WorkorderSearchResult> searchWorkorders(
            @Parameter(description = "Free-text query matching customer name or workorder id (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(description = "Exact customer id filter, combinable with q (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    UUID customerId,
            @Parameter(description = "Exact vehicle id filter, combinable with q (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    UUID vehicleId,
            @Parameter(schema = @Schema(implementation = Pageable.class)) @PageableDefault(size = 25)
                    Pageable pageable) {
        return workorderSearchService.search(q == null ? "" : q.trim(), customerId, vehicleId, pageable);
    }

    @Operation(operationId = "resolveWorkorderNumbers", summary = "Resolve Workorder Ids to Numbers", description = """
                    Batch-resolves workorder ids to their human-readable workorder numbers for sibling services \
                    that store only the id.
                    Use this tool when known workorder ids need display numbers; do not use searchWorkorders, \
                    which is for discovering workorders by text query rather than resolving known ids.
                    Preconditions: none — nulls and duplicates in the id list are silently dropped, and ids with \
                    no matching workorder are omitted from the response rather than erroring.
                    Required inputs: a body with workorderIds, a non-empty list of UUIDs.
                    Emits a WORKORDER_NUMBER_RESOLVE audit event; no workorder state changes — this is a \
                    read-only projection.
                    Returns 200 with one pairing per found workorder, and 400 when workorderIds is missing or \
                    empty.
                    """)
    @ApiResponse(responseCode = "200", description = "Resolved workorder id-to-number pairings returned.")
    @PostMapping("/numbers:resolve")
    @PreAuthorize("hasAuthority('" + WorkorderPermissions.WORKORDER_VIEW + "')")
    @EmitEvent(id = "WORKORDER_NUMBER_RESOLVE", apiVersion = "1")
    public List<WorkorderNumberRef> resolveNumbers(
            @io.swagger.v3.oas.annotations.parameters.RequestBody(
                            description = "Batch of workorder ids to resolve to human workorder numbers.",
                            required = true,
                            content =
                                    @io.swagger.v3.oas.annotations.media.Content(
                                            mediaType = "application/json",
                                            examples =
                                                    @io.swagger.v3.oas.annotations.media.ExampleObject(
                                                            name = "Two ids",
                                                            value = """
                                                                    {"workorderIds":["018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a11",
                                                                                     "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a12"]}
                                                                    """)))
                    @Valid
                    @RequestBody
                    WorkorderNumberResolveRequest request) {
        return workorderSearchService.resolveNumbers(request.workorderIds());
    }
}
