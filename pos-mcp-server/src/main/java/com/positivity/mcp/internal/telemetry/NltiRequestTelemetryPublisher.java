package com.positivity.mcp.internal.telemetry;

import com.positivity.mcp.internal.security.PermissionCodes;
import com.positivity.mcp.internal.telemetry.NltiRequestTelemetryFactory.WriteSignal;
import com.positivity.mcp.service.McpRoleResolver;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Emits {@code nlti.request.telemetry} for the NLTI request pipeline ({@code POST /v1/nlt/requests}
 * and the write-plan confirm/cancel calls), which until #1397 produced no telemetry at all — only
 * the two chat managers did, leaving every {@code write.*} field of the v1 schema dead and the Gate
 * 7 write-confirmation panels on substitute queries.
 *
 * <p>This resolves the actor (primary role and permission-code count) from the security context so
 * callers do not each repeat that lookup, stamps the timestamp from the injected {@link Clock}, and
 * — the load-bearing property — <strong>never throws into the request path</strong>. Every failure
 * mode, including an absent or unusual security context, is caught and logged: a request that has
 * already been accepted or a write that has already executed must not fail because its telemetry
 * could not be written.
 */
@Component
public class NltiRequestTelemetryPublisher {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiRequestTelemetryPublisher.class);

    /**
     * Actor stamped on an event whose caller could not be resolved. The role matches
     * {@code McpRoleResolverImpl.FALLBACK_ROLE} so it groups with that resolver's own fallback on
     * the dashboards; the zero code count is distinguishable from any authenticated caller, who
     * always carries at least the synthetic {@code AUTHENTICATED} code.
     */
    private static final Actor UNRESOLVED_ACTOR = new Actor("ROLE_USER", 0);

    private final NltiTelemetryEmitter emitter;
    private final McpRoleResolver roleResolver;
    private final Clock clock;

    public NltiRequestTelemetryPublisher(
            @NonNull NltiTelemetryEmitter emitter, @NonNull McpRoleResolver roleResolver, @NonNull Clock clock) {
        this.emitter = emitter;
        this.roleResolver = roleResolver;
        this.clock = clock;
    }

    /**
     * Emits one event for an NLTI request leg.
     *
     * @param correlationId the request's correlation id — the join key the Gate 7 dashboards and
     *     the {@code --suite write-gate} harness use to find this record
     * @param sessionId the NLTI session, when one was resolved before the request ended
     * @param requestId the persisted NLTI request, when one was created before the request ended
     * @param intentType the {@code IntentV1} classification, when the request was parsed
     * @param riskLevel the intent's assessed risk level, when classified
     * @param write Gate 6 write-gate signals, or null when the request never reached the gate
     * @param totalMs wall time for this leg, or null when the event does not correspond to a call
     *     of its own
     * @param status {@code SUCCESS} or {@code ERROR}
     * @param errorCode short error discriminator, set only for {@code ERROR}
     */
    public void emit(
            @NonNull UUID correlationId,
            @Nullable UUID sessionId,
            @Nullable UUID requestId,
            @Nullable String intentType,
            @Nullable String riskLevel,
            @Nullable WriteSignal write,
            @Nullable Long totalMs,
            @NonNull String status,
            @Nullable String errorCode) {
        Actor actor = resolveActor();
        try {
            emitter.emit(NltiRequestTelemetryFactory.forNltiRequest(
                    correlationId.toString(),
                    Instant.now(clock).toString(),
                    sessionId,
                    requestId,
                    actor.primaryRole(),
                    actor.permissionCodeCount(),
                    intentType,
                    riskLevel,
                    write,
                    totalMs,
                    status,
                    errorCode));
        } catch (RuntimeException telemetryFailure) {
            LOGGER.warn(
                    "MCP NLTI request telemetry emission failed correlationId={} status={}",
                    correlationId,
                    status,
                    telemetryFailure);
        }
    }

    /**
     * Best-effort actor for the event. Resolution is guarded separately from emission on purpose:
     * the value of this record is its correlation id and its write-gate outcome, neither of which
     * depends on who the caller was, so an unresolvable security context must degrade the actor
     * rather than drop the record. {@code SecurityContextHelper.getAuthorities()} throws outright
     * when nothing is authenticated, which is precisely the case that would otherwise lose events.
     */
    private @NonNull Actor resolveActor() {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            if (authentication == null) {
                return UNRESOLVED_ACTOR;
            }
            return new Actor(
                    roleResolver.resolvePrimaryRole(authentication),
                    // The same gating signal the chat path reports, so actor.permissionCodeCount
                    // means one thing across both emitters (bare codes plus AUTHENTICATED, never
                    // the raw ROLE_/PERM_ authority set).
                    PermissionCodes.extract(SecurityContextHelper.getAuthorities())
                            .size());
        } catch (RuntimeException unresolvableActor) {
            LOGGER.debug("MCP NLTI telemetry actor unresolved; emitting with the fallback actor", unresolvableActor);
            return UNRESOLVED_ACTOR;
        }
    }

    /** Resolved caller identity for one event. */
    private record Actor(@NonNull String primaryRole, int permissionCodeCount) {}
}
