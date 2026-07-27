package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.domain.WorkflowState;
import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
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
 * session). {@link #advance(UUID, String, WorkflowState)} is the explicit write path that moves a
 * session to a non-IDLE state as a workflow progresses; it is ownership-checked so a subject can only
 * mutate their own session.
 */
@Service
public class NltiWorkflowStateService {

    private static final Logger LOGGER = LoggerFactory.getLogger(NltiWorkflowStateService.class);

    private final NltiSessionRepository sessionRepository;

    public NltiWorkflowStateService(@NonNull NltiSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
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
     * @throws SessionOwnershipViolationException when the session does not exist or is not owned by
     *     the subject — matching {@code NltiRequestServiceImpl}'s ownership posture.
     */
    @Transactional
    public @NonNull WorkflowState advance(
            @NonNull UUID sessionId, @NonNull String subjectId, @NonNull WorkflowState newState) {
        NltiSession session = sessionRepository
                .findByIdAndSubjectId(sessionId, subjectId)
                .orElseThrow(() -> new SessionOwnershipViolationException(
                        "Session is not owned by the authenticated subject or does not exist: " + sessionId));
        WorkflowState previous = session.getWorkflowState();
        session.setWorkflowState(newState);
        NltiSession saved = sessionRepository.save(session);
        LOGGER.info("NLTI session {} workflow state advanced {} -> {}", sessionId, previous, newState);
        return saved.getWorkflowState();
    }
}
