package com.positivity.mcp.internal.controller;

import com.positivity.events.EmitEvent;
import com.positivity.mcp.internal.domain.EvalTurnTrace;
import com.positivity.mcp.internal.security.McpPermissions;
import com.positivity.mcp.internal.service.EvalTurnTraceQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read path for recorded eval turn traces (#1706 ask 2).
 *
 * <p>The traces have been written since #1682 — offered tools, tool calls, the assembled system
 * prompt, routing and workflow state — and nothing could read them. That gap is what blocked
 * #1709: its grader reads the window shape out of the model's ANSWER, on the assumption that the
 * DATE_WINDOW contract to quote the resolver statement holds. On 2026-09-04 it did not, for six of
 * twelve questions, while the service log showed sixteen correct resolver calls. The shape was
 * recorded and unreachable.
 *
 * <p>Deliberately scoped to the caller's own traces. A trace carries the assembled system prompt,
 * the user's message and every tool result, so cross-user reads would turn an eval convenience into
 * a disclosure surface. The gate runner authenticates as the actor whose turns it wants, so this
 * costs it nothing.
 *
 * <p>Registered only when the recorder itself is enabled. An endpoint that exists while nothing
 * writes would return empty results indistinguishable from "the feature is off", which is the class
 * of ambiguity #1706 exists to remove.
 */
@RestController
@io.swagger.v3.oas.annotations.security.SecurityRequirement(
        name = "bearerAuth",
        scopes = {"mcp:eval_trace:view"})
@RequestMapping("/v1/eval/turn-traces")
@Tag(name = "Eval Turn Traces", description = "Recorded per-turn evaluation traces")
@Profile("alpha")
@ConditionalOnProperty(name = "mcp.eval.turn-trace.enabled", havingValue = "true")
class EvalTurnTraceController {

    private final EvalTurnTraceQueryService queryService;

    EvalTurnTraceController(@NonNull EvalTurnTraceQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    @EmitEvent(id = "MCP_EVAL_TRACE_QUERY", apiVersion = "1")
    @PreAuthorize("hasAuthority('" + McpPermissions.MCP_EVAL_TRACE_VIEW + "')")
    @Operation(
            operationId = "listEvalTurnTraces",
            summary = "List the calling actor's recorded eval turn traces",
            description = """
                    Returns the authenticated caller's own turn traces recorded at or after `since`, newest \
                    first. Each trace carries the tools offered for that turn, the tool calls made with their \
                    arguments and results, the assembled system prompt, the router's decision and the final \
                    response.

                    Intended for the analytics gate runner, which needs the tools and window shapes a turn \
                    actually used rather than what its prose happened to disclose. Traces of other actors are \
                    never returned, whatever permissions the caller holds.
                    """)
    ResponseEntity<List<EvalTurnTrace>> list(
            @RequestParam("since") @NonNull Instant since,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @NonNull Authentication authentication) {
        return ResponseEntity.ok(queryService.findForCaller(authentication.getName(), since, limit));
    }
}
