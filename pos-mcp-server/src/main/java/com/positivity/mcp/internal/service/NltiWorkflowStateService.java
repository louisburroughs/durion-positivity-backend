package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionEmitter;
import com.positivity.mcp.internal.telemetry.NltiWorkflowTransitionTelemetryFactory;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads and advances the session-owned {@link WorkflowState} on {@link NltiSession} (#778).
 *
 * <p>The session-less chat path uses {@link #resolveActiveState(String)} to gate tool selection by
 * the subject's persisted workflow state (falling back to message heuristics when the subject has no
 * session). {@link #advance(UUID, String, WorkflowState, UUID)} is the explicit write path that moves
 * a session to a non-IDLE state as a workflow progresses; it is ownership-checked so a subject can
 * only mutate their own session, and it emits the Gate 2C {@code nlti.workflow.transition} telemetry
 * event for the from -&gt; to move.
 */
@Service
public class NltiWorkflowStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiWorkflowStateService.class);

    private final NltiSessionRepository sessionRepository;
    private final NltiWorkflowTransitionEmitter transitionEmitter;
    private final Clock clock;

    public NltiWorkflowStateService(
            @NonNull NltiSessionRepository sessionRepository,
            @NonNull NltiWorkflowTransitionEmitter transitionEmitter,
            @NonNull Clock clock) {
        this.sessionRepository = sessionRepository;
        this.transitionEmitter = transitionEmitter;
        this.clock = clock;
    }

    /**
     * The subject's active (most-recently-updated) session workflow state, or empty when the subject
     * has no session. An empty result tells the caller to fall back to message-heuristic derivation.
     */
    @Transactional(readOnly = true)
    public @NonNull Optional<WorkflowState> resolveActiveState(@NonNull String subjectId) {
        return sessionRepository
                .findFirstBySubjectIdOrderByUpdatedAtDesc(subjectId)
                .map(NltiSession::getWorkflowState);
    }

    /**
     * Advances the given session (which must belong to {@code subjectId}) to {@code newState} and
     * returns the persisted state. Returns the {@link WorkflowState} (not the entity) so callers such
     * as controllers never depend on the persistence layer.
     *
     * <p>Gate 2C: on success this emits one {@code nlti.workflow.transition} telemetry event carrying
     * the session id, the from/to states, the actor subject, the caller's correlation id, and the
     * transition timestamp. Emission happens after the state has been persisted and never throws into
     * the request path, so a telemetry failure cannot fail (or roll back) an accepted transition.
     *
     * @param correlationId the correlation id of the request performing the transition, so the event
     *     joins to that request's {@code nlti.request.telemetry} lines
     * @throws SessionOwnershipViolationException when the session does not exist or is not owned by
     *     the subject — matching {@code NltiRequestServiceImpl}'s ownership posture.
     */
    @Transactional
    public @NonNull WorkflowState advance(
            @NonNull UUID sessionId,
            @NonNull String subjectId,
            @NonNull WorkflowState newState,
            @NonNull UUID correlationId) {
        NltiSession session = sessionRepository
                .findByIdAndSubjectId(sessionId, subjectId)
                .orElseThrow(() -> new SessionOwnershipViolationException(
                        "Session is not owned by the authenticated subject or does not exist: " + sessionId));
        WorkflowState previous = session.getWorkflowState();
        session.setWorkflowState(newState);
        NltiSession saved = sessionRepository.save(session);
        WorkflowState persisted = saved.getWorkflowState();
        LOGGER.info("NLTI session {} workflow state advanced {} -> {}", sessionId, previous, persisted);
        emitTransitionTelemetry(correlationId, sessionId, subjectId, previous, persisted);
        return persisted;
    }

    /**
     * Emits the workflow-transition telemetry event. Never throws: the transition is already
     * persisted, so a telemetry failure is logged and swallowed rather than surfaced to the caller.
     */
    private void emitTransitionTelemetry(
            @NonNull UUID correlationId,
            @NonNull UUID sessionId,
            @NonNull String subjectId,
            @NonNull WorkflowState fromState,
            @NonNull WorkflowState toState) {
        try {
            transitionEmitter.emit(NltiWorkflowTransitionTelemetryFactory.forTransition(
                    correlationId.toString(), Instant.now(clock).toString(), sessionId, subjectId, fromState, toState));
        } catch (RuntimeException telemetryFailure) {
            LOGGER.warn(
                    "MCP workflow transition telemetry emission failed sessionId={} from={} to={}",
                    sessionId,
                    fromState,
                    toState,
                    telemetryFailure);
        }
    }
}
