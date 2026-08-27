package com.positivity.poseventreceiver.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.poseventreceiver.internal.dto.EmittedEventResponse;
import com.positivity.poseventreceiver.internal.dto.PagedResponse;
import com.positivity.poseventreceiver.service.EventQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for querying recorded events by entity id (issue #1521).
 *
 * <h2>Security Model</h2>
 * <p>
 * Uses the same shared-secret security filter ({@code EventsApiSecurityFilter}) as the
 * other controllers in this module; GET requests are allowed without authentication per
 * the existing security filter policy. This endpoint is reached externally only through
 * the API gateway, which enforces JWT authentication before proxying the request; no
 * finer-grained permission is enforced here, so the MCP facade seed for this endpoint is
 * deliberately AUTHENTICATED rather than a specific scope.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
@RestController
@Validated
@RequestMapping("/v1/events")
@Tag(name = "Event Query", description = "Query recorded events by entity id")
public class EventQueryController {

    private final EventQueryService eventQueryService;

    @GetMapping
    @EmitEvent(id = "EVENT_RECEIVER_EVENT_QUERY", apiVersion = "1")
    @Operation(
            operationId = "queryEventsByEntity",
            summary = "Query recorded events for one entity",
            description = """
                    Returns a page of emitted-event occurrences recorded against one entityId, newest first by \
                    publishedAt, for services that tag their @EmitEvent calls with an entity id.
                    Use this tool to reconstruct the event history of one known entity; use the summary endpoints \
                    instead when only aggregate counts by event type are needed, and note that events recorded \
                    without an entityId are never returned here regardless of which entity they actually relate to.
                    Preconditions: caller is authenticated at the API gateway; no finer-grained permission is \
                    enforced by this service, and the entityId need not have any events recorded for it yet.
                    Required inputs: entityId (max 64 characters); since defaults to 7 days ago and is rejected when \
                    it is in the future or more than 90 days in the past; page defaults to 0 and size defaults to 50 \
                    with a maximum of 200.
                    No events are emitted other than EVENT_RECEIVER_EVENT_QUERY recording the query itself; results \
                    can lag real time by up to about 5 seconds because writes are batched, and rows older than the \
                    compression threshold may be slower to scan.
                    Returns 200 with a possibly-empty page, and 400 when since is out of its allowed range or page \
                    or size fall outside their bounds.
                    """,
            tags = {"Event Query"})
    @ApiResponse(
            responseCode = "200",
            description = "Page of matching events returned",
            content = @Content(schema = @Schema(implementation = PagedResponse.class)))
    @ApiResponse(responseCode = "400", description = "since is out of the allowed range, or page/size are invalid")
    public ResponseEntity<PagedResponse<EmittedEventResponse>> queryEventsByEntity(
            @Parameter(
                            description = "Entity id events were recorded against",
                            required = true,
                            example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
                    @RequestParam
                    @NotBlank
                    @Size(max = 64)
                    String entityId,
            @Parameter(
                            description = "Lower bound on publishedAt, inclusive. Defaults to 7 days ago; must not "
                                    + "be in the future or more than 90 days in the past.",
                            example = "2026-08-20T00:00:00Z")
                    @RequestParam(required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant since,
            @Parameter(description = "Zero-based page index", example = "0")
                    @RequestParam(defaultValue = "0")
                    @PositiveOrZero
                    int page,
            @Parameter(description = "Page size, at most 200", example = "50")
                    @RequestParam(defaultValue = "50")
                    @Positive
                    @Max(200)
                    int size) {
        log.debug(
                "Querying events by entityId(mask)={}, since={}, page={}, size={}",
                maskForLog(entityId),
                since,
                page,
                size);
        try {
            return ResponseEntity.ok(eventQueryService.findByEntity(entityId, since, page, size));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid event query request for entityId(mask) {}: {}", maskForLog(entityId), e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    private String maskForLog(Object value) {
        if (value == null) {
            return "null";
        }
        String sanitized =
                value.toString().replace('\r', '_').replace('\n', '_').replace('\t', '_');
        int length = sanitized.length();
        if (length <= 4) {
            return "****";
        }
        return sanitized.substring(0, 2) + "***" + sanitized.substring(length - 2);
    }
}
