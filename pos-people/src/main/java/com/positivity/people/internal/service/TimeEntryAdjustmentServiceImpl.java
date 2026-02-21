package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.TimeEntryAdjustmentService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeEntryAdjustmentServiceImpl implements TimeEntryAdjustmentService {
    private final TimeEntryAdjustmentRepository adjustmentRepository;
    private final TimeEntryAuditRepository auditRepository;
    private final TimeEntryRepository timeEntryRepository;

    public TimeEntryAdjustmentServiceImpl(TimeEntryAdjustmentRepository adjustmentRepository,
            TimeEntryAuditRepository auditRepository,
            TimeEntryRepository timeEntryRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.auditRepository = auditRepository;
        this.timeEntryRepository = timeEntryRepository;
    }

    @Override
    @Transactional
    @NonNull
    public TimeEntryAdjustmentResponse createAdjustment(@NonNull TimeEntryAdjustmentRequest request) {
        if (request.getReasonCode() == null || request.getReasonCode().isBlank()) {
            throw new IllegalArgumentException("reasonCode is required");
        }

        if (request.getTimeEntryId() == null || request.getTimeEntryId().isBlank()) {
            throw new IllegalArgumentException("timeEntryId is required");
        }

        final UUID timeEntryUuid;
        try {
            timeEntryUuid = UUID.fromString(request.getTimeEntryId());
        } catch (IllegalArgumentException invalidTimeEntryId) {
            throw new IllegalArgumentException("timeEntryId must be a valid UUID", invalidTimeEntryId);
        }

        Optional<com.positivity.people.internal.entity.TimeEntry> entryOptional = timeEntryRepository
                .findById(timeEntryUuid);
        if (entryOptional.isEmpty()) {
            throw new NotFoundException("Time entry not found");
        }
        com.positivity.people.internal.entity.TimeEntry entry = entryOptional.get();
        if (entry.getStatus() != com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Adjustments can only be created for entries in PENDING_APPROVAL status");
        }

        boolean hasProposedTimes = request.getProposedStartAt() != null || request.getProposedEndAt() != null;
        boolean hasMinutesDelta = request.getMinutesDelta() != null;
        if (!(hasProposedTimes ^ hasMinutesDelta)) {
            throw new IllegalArgumentException(
                    "Provide either both proposedStartAt and proposedEndAt, OR minutesDelta (exactly one)");
        }

        if (hasProposedTimes && (request.getProposedStartAt() == null || request.getProposedEndAt() == null)) {
            throw new IllegalArgumentException(
                    "Both proposedStartAt and proposedEndAt must be provided together");
        }

        com.positivity.people.internal.entity.TimeEntryAdjustment adjustment = new com.positivity.people.internal.entity.TimeEntryAdjustment();
        adjustment.setTimeEntryId(request.getTimeEntryId());
        adjustment.setReasonCode(request.getReasonCode());
        adjustment.setNotes(request.getNotes());
        if (request.getProposedStartAt() != null) {
            adjustment.setProposedStartAt(request.getProposedStartAt().toInstant());
        }
        if (request.getProposedEndAt() != null) {
            adjustment.setProposedEndAt(request.getProposedEndAt().toInstant());
        }
        adjustment.setMinutesDelta(request.getMinutesDelta());
        adjustment.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.PENDING);
        adjustment.setCreatedBy(request.getCreatedBy());
        adjustment.setCreatedAt(Instant.now());

        com.positivity.people.internal.entity.TimeEntryAdjustment saved = adjustmentRepository.save(adjustment);
        return new TimeEntryAdjustmentResponse(saved.getAdjustmentId(), true, "created");
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<com.positivity.people.internal.dto.TimeEntryAdjustment> listForTimeEntry(@NonNull String timeEntryId) {
        return adjustmentRepository.findByTimeEntryId(timeEntryId).stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public boolean approveAdjustment(java.util.UUID adjustmentId, String approverUserId, Set<String> permissions,
            String correlationId) {
        String auditActorId = SecurityContextHelper.getCurrentUserIdOrThrowIllegalStateException();
        Optional<com.positivity.people.internal.entity.TimeEntryAdjustment> opt = adjustmentRepository
                .findById(adjustmentId);
        if (opt.isEmpty()) {
            throw new NotFoundException("Adjustment not found: " + adjustmentId);
        }
        com.positivity.people.internal.entity.TimeEntryAdjustment adj = opt.get();

        boolean allowed = permissions != null && (permissions.contains("people:timeAdjustment:approve")
                || permissions.contains("admin"));
        if (!allowed) {
            try {
                TimeEntryAudit audit = new TimeEntryAudit();
                audit.setTimeEntryId(adj.getTimeEntryId());
                audit.setAction("ADJUSTMENT_APPROVE_FORBIDDEN");
                audit.setActorId(auditActorId);
                audit.setCorrelationId(correlationId);
                audit.setDetails("Permission denied for adjustment approval");
                auditRepository.save(audit);
            } catch (Exception ignore) {
                // Audit logging failure should not prevent adjustment approval flow.
            }
            throw new AccessDeniedException("Permission denied for adjustment approval");
        }

        adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.APPROVED);
        adj.setDecidedBy(approverUserId);
        adj.setDecidedAt(Instant.now());
        adjustmentRepository.save(adj);

        try {
            TimeEntryAudit audit = new TimeEntryAudit();
            audit.setTimeEntryId(adj.getTimeEntryId());
            audit.setAction("ADJUSTMENT_APPROVED");
            audit.setActorId(auditActorId);
            audit.setCorrelationId(correlationId);
            audit.setDetails("Adjustment approved");
            auditRepository.save(audit);
        } catch (Exception ignore) {
            // Audit logging failure should not prevent adjustment approval flow.
        }

        return true;
    }

    private com.positivity.people.internal.dto.TimeEntryAdjustment toDto(
            com.positivity.people.internal.entity.TimeEntryAdjustment adjustment) {
        com.positivity.people.internal.dto.TimeEntryAdjustment dto = new com.positivity.people.internal.dto.TimeEntryAdjustment();
        dto.setAdjustmentId(adjustment.getAdjustmentId());
        dto.setTimeEntryId(adjustment.getTimeEntryId());
        dto.setReasonCode(adjustment.getReasonCode());
        dto.setNotes(adjustment.getNotes());
        dto.setProposedStartAt(adjustment.getProposedStartAt());
        dto.setProposedEndAt(adjustment.getProposedEndAt());
        dto.setMinutesDelta(adjustment.getMinutesDelta());
        dto.setStatus(adjustment.getStatus());
        dto.setCreatedBy(adjustment.getCreatedBy());
        dto.setCreatedAt(adjustment.getCreatedAt());
        dto.setDecidedBy(adjustment.getDecidedBy());
        dto.setDecidedAt(adjustment.getDecidedAt());
        return dto;
    }
}
