package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStoppedEvent;
import com.positivity.workorder.internal.dto.AddBreakSegmentRequest;
import com.positivity.workorder.internal.dto.BreakSegmentResponse;
import com.positivity.workorder.internal.dto.StartWorkSessionRequest;
import com.positivity.workorder.internal.dto.StopWorkSessionRequest;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import com.positivity.workorder.internal.entity.BreakSegment;
import com.positivity.workorder.internal.entity.WorkSession;
import com.positivity.workorder.internal.enums.WorkSessionStatus;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.repository.BreakSegmentRepository;
import com.positivity.workorder.internal.repository.WorkSessionRepository;
import com.positivity.workorder.service.WorkSessionService;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full implementation of {@link WorkSessionService}.
 *
 * <p>
 * Issue: CAP-139 Story #68
 * </p>
 */
@Service
@RequiredArgsConstructor
@Transactional
public class WorkSessionServiceImpl implements WorkSessionService {

    private final WorkSessionRepository workSessionRepository;
    private final BreakSegmentRepository breakSegmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${timekeeping.allowOverlappingSessions:false}")
    private boolean allowOverlappingSessions;

    @Override
    public @NonNull WorkSessionResponse startSession(@NonNull StartWorkSessionRequest request) {
        // 1. Check overlap
        List<WorkSession> active = workSessionRepository.findByMechanicIdAndStatus(
                request.getMechanicId(), WorkSessionStatus.IN_PROGRESS);
        if (!active.isEmpty()) {
            boolean hasPermission = SecurityContextHelper.hasAuthority("timekeeping:overlap_override");
            boolean hasReason = request.getOverlapOverrideReason() != null
                    && !request.getOverlapOverrideReason().isBlank();
            if (!allowOverlappingSessions || !hasPermission || !hasReason) {
                throw new WorkSessionOverlapException(request.getMechanicId());
            }
        }

        // 2. Resolve actor from security context
        String actorId = SecurityContextHelper.getCurrentUsername().orElse("system");

        boolean overrideUsed = request.getOverlapOverrideReason() != null
                && !request.getOverlapOverrideReason().isBlank();

        // 3. Build and save session
        WorkSession session = WorkSession.builder()
                .mechanicId(request.getMechanicId())
                .workOrderId(request.getWorkOrderId())
                .workOrderTaskId(request.getWorkOrderTaskId())
                .locationId(request.getLocationId())
                .resourceId(request.getResourceId())
                .startAt(Instant.now())
                .status(WorkSessionStatus.IN_PROGRESS)
                .locked(false)
                .totalDurationSeconds(0)
                .overlapOverrideUsed(overrideUsed)
                .overrideReason(request.getOverlapOverrideReason())
                .overriddenByUserId(overrideUsed ? actorId : null)
                .overrideAt(overrideUsed ? Instant.now() : null)
                .build();

        session = workSessionRepository.save(session);

        // 4. Publish event; downstream @TransactionalEventListener(AFTER_COMMIT)
        // receivers process after commit.
        eventPublisher.publishEvent(new WorkSessionStartedEvent(
                session.getWorkSessionId(), session.getMechanicId(), session.getStartAt()));

        return toResponse(session);
    }

    @Override
    public @NonNull WorkSessionResponse stopSession(@NonNull UUID workSessionId,
            @NonNull StopWorkSessionRequest request) {
        WorkSession session = workSessionRepository.findById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));

        if (session.getStatus() != WorkSessionStatus.IN_PROGRESS) {
            throw new WorkSessionStateException("WorkSession is not in IN_PROGRESS state");
        }
        if (session.isLocked()) {
            throw new WorkSessionLockedException(workSessionId);
        }

        Instant endAt = Instant.now();
        session.setEndAt(endAt);
        session.setStatus(WorkSessionStatus.COMPLETED);

        // Compute net duration (total elapsed minus completed breaks)
        long totalSeconds = Duration.between(session.getStartAt(), endAt).toSeconds();
        List<BreakSegment> breaks = breakSegmentRepository.findByWorkSessionId(session.getWorkSessionId());
        long breakSeconds = breaks.stream()
                .filter(b -> b.getBreakEndAt() != null)
                .mapToLong(b -> Duration.between(b.getBreakStartAt(), b.getBreakEndAt()).toSeconds())
                .sum();
        session.setTotalDurationSeconds((int) Math.max(0, totalSeconds - breakSeconds));

        session = workSessionRepository.save(session);

        eventPublisher.publishEvent(new WorkSessionStoppedEvent(
                session.getWorkSessionId(), session.getMechanicId(),
                session.getEndAt(), session.getTotalDurationSeconds()));

        return toResponse(session);
    }

    @Override
    public @NonNull BreakSegmentResponse addBreakSegment(@NonNull UUID workSessionId,
            @NonNull AddBreakSegmentRequest request) {
        WorkSession session = workSessionRepository.findById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));

        if (session.getStatus() != WorkSessionStatus.IN_PROGRESS) {
            throw new WorkSessionStateException("Cannot add break: work session is not in IN_PROGRESS state");
        }
        if (session.isLocked()) {
            throw new WorkSessionLockedException(workSessionId);
        }

        // Guard: reject if an open break already exists
        breakSegmentRepository.findFirstByWorkSessionIdAndBreakEndAtIsNull(workSessionId)
                .ifPresent(existing -> {
                    throw new WorkSessionStateException(
                            "A break segment is already open for this work session. Stop it before starting another.");
                });

        BreakSegment seg = BreakSegment.builder()
                .workSession(session)
                .workSessionId(session.getWorkSessionId())
                .breakStartAt(Instant.now())
                .breakType(request.getBreakType())
                .notes(request.getNotes())
                .build();

        seg = breakSegmentRepository.save(seg);
        return toBreakResponse(seg);
    }

    @Override
    public @NonNull BreakSegmentResponse stopBreakSegment(@NonNull UUID workSessionId,
            @NonNull UUID breakSegmentId) {
        WorkSession session = workSessionRepository.findById(workSessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException(workSessionId));

        if (session.isLocked()) {
            throw new WorkSessionLockedException(workSessionId);
        }

        BreakSegment seg = breakSegmentRepository.findById(breakSegmentId)
                .orElseThrow(() -> new BreakSegmentNotFoundException(breakSegmentId));

        if (!seg.getWorkSessionId().equals(workSessionId)) {
            throw new BreakSegmentNotFoundException(breakSegmentId, workSessionId);
        }

        if (seg.getBreakEndAt() != null) {
            throw new WorkSessionStateException("Break segment is already stopped");
        }

        seg.setBreakEndAt(Instant.now());
        seg = breakSegmentRepository.save(seg);
        return toBreakResponse(seg);
    }

    // ── Mappers ──────────────────────────────────────────────────────────────

    private WorkSessionResponse toResponse(WorkSession s) {
        return WorkSessionResponse.builder()
                .workSessionId(s.getWorkSessionId())
                .mechanicId(s.getMechanicId())
                .workOrderId(s.getWorkOrderId())
                .workOrderTaskId(s.getWorkOrderTaskId())
                .locationId(s.getLocationId())
                .resourceId(s.getResourceId())
                .startAt(s.getStartAt())
                .endAt(s.getEndAt())
                .status(s.getStatus())
                .locked(s.isLocked())
                .totalDurationSeconds(s.getTotalDurationSeconds())
                .approvedAt(s.getApprovedAt())
                .approvedByUserId(s.getApprovedByUserId())
                .approvalNotes(s.getApprovalNotes())
                .lockedAt(s.getLockedAt())
                .overlapOverrideUsed(s.isOverlapOverrideUsed())
                .overrideReason(s.getOverrideReason())
                .build();
    }

    private BreakSegmentResponse toBreakResponse(BreakSegment b) {
        return BreakSegmentResponse.builder()
                .breakSegmentId(b.getBreakSegmentId())
                .workSessionId(b.getWorkSessionId())
                .breakStartAt(b.getBreakStartAt())
                .breakEndAt(b.getBreakEndAt())
                .breakType(b.getBreakType())
                .notes(b.getNotes())
                .build();
    }
}
