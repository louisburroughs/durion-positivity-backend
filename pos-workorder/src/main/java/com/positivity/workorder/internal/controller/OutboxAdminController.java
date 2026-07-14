package com.positivity.workorder.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.workorder.service.OutboxReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administrative outbox operations (ADR-0044 §4 backfill/bootstrap).
 */
@RestController
@RequiredArgsConstructor
@Tag(
        name = "Outbox Administration",
        description = "Re-emit published domain events to seed or repair consumer replicas")
public class OutboxAdminController {

    private final OutboxReplayService outboxReplayService;

    @PostMapping("/v1/outbox/replay")
    @PreAuthorize("hasAuthority('workorder:events:replay')")
    @EmitEvent(id = "WORKORDER_OUTBOX_REPLAY", apiVersion = "1")
    @Operation(
            summary = "Re-emit published domain events",
            description = "Marks already-published outbox events created at or after the given instant for"
                    + " re-publication to Kafka. Used to seed a new consumer replica or repair drift"
                    + " (ADR-0044). Consumers deduplicate by eventId, so replay is safe.")
    @ApiResponse(responseCode = "200", description = "Replay queued; body reports how many events were re-queued")
    @ApiResponse(responseCode = "400", description = "Malformed 'since' timestamp")
    @ApiResponse(responseCode = "401", description = "Missing or invalid authentication")
    @ApiResponse(responseCode = "403", description = "Caller lacks workorder:events:replay")
    public ResponseEntity<OutboxReplayResponse> replay(
            @Parameter(
                            description = "Re-emit events created at or after this instant (ISO-8601)."
                                    + " Defaults to the epoch, i.e. replay everything.",
                            example = "2026-07-01T00:00:00Z")
                    @RequestParam(name = "since", required = false)
                    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
                    Instant since) {
        Instant effectiveSince = since == null ? Instant.EPOCH : since;
        int queued = outboxReplayService.replaySince(effectiveSince);
        return ResponseEntity.ok(new OutboxReplayResponse(effectiveSince, queued));
    }

    @Schema(description = "Result of an outbox replay request")
    public record OutboxReplayResponse(
            @Schema(description = "Effective lower bound used for the replay")
            Instant since,

            @Schema(description = "Number of events re-queued for publication")
            int eventsQueued) {}
}
