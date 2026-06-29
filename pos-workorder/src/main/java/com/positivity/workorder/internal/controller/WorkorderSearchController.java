package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderNumberResolveRequest;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.service.WorkorderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
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

    @Operation(
            summary = "Search workorders",
            description = "Paginated free-text search for workorders matching customer name or workorder id.")
    @ApiResponse(responseCode = "200", description = "Page of workorder search results returned.")
    @GetMapping("/search")
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    @EmitEvent(id = "WORKORDER_SEARCH", apiVersion = "1")
    public Page<WorkorderSearchResult> searchWorkorders(
            @Parameter(description = "Free-text query matching customer name or workorder id (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    String q,
            @Parameter(schema = @Schema(implementation = Pageable.class)) @PageableDefault(size = 25)
                    Pageable pageable) {
        return workorderSearchService.search(q == null ? "" : q.trim(), pageable);
    }

    @Operation(
            summary = "Resolve workorder numbers",
            description = "Batch-resolves a set of workorder ids to their human workorder numbers. "
                    + "Consumed server-side by sibling services that store only the workorder id "
                    + "and need the human number for finder/search enrichment.")
    @ApiResponse(responseCode = "200", description = "Resolved workorder id-to-number pairings returned.")
    @PostMapping("/numbers:resolve")
    @PreAuthorize("hasAuthority('workorder:workorder:view')")
    @EmitEvent(id = "WORKORDER_NUMBER_RESOLVE", apiVersion = "1")
    public List<WorkorderNumberRef> resolveNumbers(@Valid @RequestBody WorkorderNumberResolveRequest request) {
        return workorderSearchService.resolveNumbers(request.workorderIds());
    }
}
