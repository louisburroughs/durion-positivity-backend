package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.internal.dto.WorkorderNumberRef;
import com.positivity.workorder.internal.dto.WorkorderNumberResolveRequest;
import com.positivity.workorder.internal.dto.WorkorderSearchResult;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.security.WorkorderPermissions;
import com.positivity.workorder.internal.service.WorkorderSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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

    /**
     * Hard cap on page size so a caller cannot request an unbounded enrichment fan-out (each row
     * resolves a customer and vehicle reference). Mirrors {@code InvoiceSearchController}'s
     * {@code capPageSize} idiom (#1587/pos-invoice): the response is a Spring Data {@link Page},
     * whose {@code size}/{@code totalElements} already tell a caller when it received fewer rows
     * than requested, so a capped request is self-signalling without adding a bespoke
     * {@code truncated} field to the (unchanged) response shape.
     */
    private static final int MAX_PAGE_SIZE = 100;

    private static final int DEFAULT_PAGE_SIZE = 25;

    @Operation(operationId = "searchWorkorders", summary = "Search Workorders With Filters", description = """
                    Searches workorders by free-text query against customer display names or a literal workorder \
                    id, optionally narrowed by exact customerId and vehicleId filters, an exact status, a \
                    createdAt date window (createdFrom/createdTo), and/or a technicianId, returning a page of \
                    rows enriched with customer name, vehicle label, and VIN. All filters are combinable with \
                    each other and with q.
                    status must be an exact WorkorderStatus value (DRAFT, APPROVED, ASSIGNED, WORK_IN_PROGRESS, \
                    AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP, COMPLETED, CANCELLED); an unrecognized \
                    value is rejected with 400 rather than silently matching nothing. There is no "open" alias — \
                    an "open work orders" query loops this call once per open status (APPROVED, ASSIGNED, \
                    WORK_IN_PROGRESS, AWAITING_PARTS, AWAITING_APPROVAL, READY_FOR_PICKUP), each call still fully \
                    server-side and combinable with customerId etc., the same way a caller loops a date window \
                    over several periods for other endpoints in this API.
                    technicianId matches any technician who has logged a labor entry \
                    (WorkorderLaborEntry.technicianId) on the workorder — the same attribution basis as \
                    getTechnicianLaborAnalytics's billedHours column — not the workorder's currently assigned \
                    technician (TechnicianAssignment); a workorder assigned to one technician but worked by \
                    another surfaces under the working technician's id.
                    createdFrom/createdTo are inclusive calendar-date bounds (YYYY-MM-DD) on the workorder's \
                    createdAt timestamp, evaluated in UTC.
                    Use this tool when finding workorders by customer, id fragments, status, date, or \
                    technician; use listWorkorders instead for an unfiltered listing, and \
                    resolveWorkorderNumbers to map known ids to human numbers.
                    Preconditions: customer-name matching depends on the local customer replica; at most 10 \
                    name-matched customers are considered per query.
                    Required inputs: none are mandatory — q defaults to an empty string and is treated as a \
                    workorder id when it parses as a UUID; page size defaults to 25 and is hard-capped at 100 \
                    (a larger request is silently clamped, visible in the response's own size/totalElements).
                    Emits a WORKORDER_SEARCH audit event; no workorder state changes — this is a read-only \
                    projection.
                    Returns 200 with an empty page when nothing matches, 400 when status is not a valid \
                    WorkorderStatus value, and no 404 for empty results.
                    """)
    @ApiResponse(responseCode = "200", description = "Page of workorder search results returned.")
    @ApiResponse(responseCode = "400", description = "status is not a valid WorkorderStatus value.")
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
            @Parameter(
                            description = "Exact status filter (optional). Must be a real WorkorderStatus value; "
                                    + "an unrecognized value returns 400. No \"open\" alias — loop this call once "
                                    + "per open status to find open work orders.")
                    @RequestParam(required = false)
                    @Nullable
                    WorkorderStatus status,
            @Parameter(description = "Inclusive lower bound (YYYY-MM-DD, UTC) on the workorder's createdAt (optional)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate createdFrom,
            @Parameter(description = "Inclusive upper bound (YYYY-MM-DD, UTC) on the workorder's createdAt (optional)")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    @Nullable
                    LocalDate createdTo,
            @Parameter(
                            description = "Technician who logged a labor entry on the workorder (WorkorderLaborEntry"
                                    + ".technicianId) — not the assigned technician (optional)")
                    @RequestParam(required = false)
                    @Nullable
                    UUID technicianId,
            @ParameterObject @PageableDefault(size = DEFAULT_PAGE_SIZE) Pageable pageable) {
        return workorderSearchService.search(
                q == null ? "" : q.trim(),
                customerId,
                vehicleId,
                status,
                createdFrom,
                createdTo,
                technicianId,
                capPageSize(pageable));
    }

    /** Clamp the requested page size to {@link #MAX_PAGE_SIZE}, preserving page index and sort. */
    private static Pageable capPageSize(Pageable pageable) {
        if (pageable.isPaged() && pageable.getPageSize() > MAX_PAGE_SIZE) {
            return PageRequest.of(pageable.getPageNumber(), MAX_PAGE_SIZE, pageable.getSort());
        }
        return pageable;
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
