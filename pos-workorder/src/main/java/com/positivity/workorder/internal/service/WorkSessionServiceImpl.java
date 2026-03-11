package com.positivity.workorder.internal.service;

import java.time.Clock;

import com.positivity.workorder.internal.domain.WorkSessionStartedEvent;
import com.positivity.workorder.internal.domain.WorkSessionStoppedEvent;
import com.positivity.workorder.internal.dto.AddBreakSegmentRequest;
import com.positivity.workorder.internal.dto.BreakSegmentResponse;
import com.positivity.workorder.internal.dto.StartWorkSessionRequest;
import com.positivity.workorder.internal.dto.StopWorkSessionRequest;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import com.positivity.workorder.internal.entity.BreakSegment;
import com.positivity.workorder.internal.entity.WorkSession;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.WorkSessionStatus;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.exception.BreakSegmentNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionLockedException;
import com.positivity.workorder.internal.exception.WorkSessionNotFoundException;
import com.positivity.workorder.internal.exception.WorkSessionOverlapException;
import com.positivity.workorder.internal.exception.WorkSessionStateException;
import com.positivity.workorder.internal.exception.WorkorderNotFoundException;
import com.positivity.workorder.internal.repository.BreakSegmentRepository;
import com.positivity.workorder.internal.repository.WorkSessionRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
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
    private final Clock clock;

    private final WorkSessionRepository workSessionRepository;
    private final BreakSegmentRepository breakSegmentRepository;
    private final WorkorderRepository workorderRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${timekeeping.allowOverlappingSessions:false}")
    private boolean allowOverlappingSessions;

    @Override
    public @NonNull WorkSessionResponse startSession(@NonNull StartWorkSessionRequest request) {
        // 1. Check overlap
        List<WorkSession> active = workSessionRepository.findByMechanicIdAndStatus(
                request.getMechanicId(), WorkSessionStatus.IN_PROGRESS);
        String actorId = SecurityContextHelper.getCurrentUsername().orElse("system");
        boolean overrideUsed = false;
        String overrideReason = null;
        String overriddenByUserId = null;
        Instant overrideAt = null;
        if (!active.isEmpty()) {
            boolean hasPermission = SecurityContextHelper.hasAuthority("timekeeping:overlap_override");
            boolean hasReason = request.getOverlapOverrideReason() != null
                    && !request.getOverlapOverrideReason().isBlank();
            if (!allowOverlappingSessions || !hasPermission || !hasReason) {
                throw new WorkSessionOverlapException(request.getMechanicId());
            }
            overrideUsed = true;
            overrideReason = request.getOverlapOverrideReason();
            overriddenByUserId = actorId;
            overrideAt = Instant.now(clock);
        }

        // 3. Build and save session
        Workorder workorder = workorderRepository.findById(request.getWorkOrderId())
                .orElseThrow(() -> new WorkorderNotFoundException(request.getWorkOrderId()));

        WorkSession session = WorkSession.builder()
                .mechanicId(request.getMechanicId())
                .workOrder(workorder)
                .workOrderTaskId(request.getWorkOrderTaskId())
                .locationId(request.getLocationId())
                .resourceId(request.getResourceId())
                .startAt(Instant.now(clock))
                .status(WorkSessionStatus.IN_PROGRESS)
                .locked(false)
                .totalDurationSeconds(0)
                .overlapOverrideUsed(overrideUsed)
                .overrideReason(overrideReason)
                .overriddenByUserId(overriddenByUserId)
                .overrideAt(overrideAt)
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

        Instant endAt = Instant.now(clock);
        session.setEndAt(endAt);
        session.setStatus(WorkSessionStatus.COMPLETED);

        // Compute net duration (total elapsed minus completed breaks)
        long totalSeconds = Duration.between(session.getStartAt(), endAt).toSeconds();
        List<BreakSegment> breaks = breakSegmentRepository.findByWorkSession_WorkSessionId(session.getWorkSessionId());
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
        breakSegmentRepository.findFirstByWorkSession_WorkSessionIdAndBreakEndAtIsNull(workSessionId)
                .ifPresent(existing -> {
                    throw new WorkSessionStateException(
                            "A break segment is already open for this work session. Stop it before starting another.");
                });

        BreakSegment seg = BreakSegment.builder()
                .workSession(session)
                .breakStartAt(Instant.now(clock))
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

        if (!seg.getWorkSession().getWorkSessionId().equals(workSessionId)) {
            throw new BreakSegmentNotFoundException(breakSegmentId, workSessionId);
        }

        if (seg.getBreakEndAt() != null) {
            throw new WorkSessionStateException("Break segment is already stopped");
        }

        seg.setBreakEndAt(Instant.now(clock));
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
                .workSessionId(b.getWorkSession().getWorkSessionId())
                .breakStartAt(b.getBreakStartAt())
                .breakEndAt(b.getBreakEndAt())
                .breakType(b.getBreakType())
                .notes(b.getNotes())
                .build();
    }
}
