package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryAdjustmentRequest;
import com.positivity.people.internal.dto.TimeEntryAdjustmentResponse;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class TimeEntryAdjustmentService {

    private final TimeEntryAdjustmentRepository adjustmentRepository;
    private final TimeEntryAuditRepository auditRepository;
    private final TimeEntryRepository timeEntryRepository;

    public TimeEntryAdjustmentService(TimeEntryAdjustmentRepository adjustmentRepository,
            TimeEntryAuditRepository auditRepository,
            TimeEntryRepository timeEntryRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.auditRepository = auditRepository;
        this.timeEntryRepository = timeEntryRepository;
    }

    @Transactional
    public TimeEntryAdjustmentResponse createAdjustment(TimeEntryAdjustmentRequest request) {
        if (request.getReasonCode() == null || request.getReasonCode().isBlank()) {
            return new TimeEntryAdjustmentResponse(null, false, "reasonCode is required");
        }

        if (request.getTimeEntryId() == null || request.getTimeEntryId().isBlank()) {
            return new TimeEntryAdjustmentResponse(null, false, "timeEntryId is required");
        }

        try {
            Optional<com.positivity.people.internal.entity.TimeEntry> entryOptional = timeEntryRepository
                    .findById(UUID.fromString(request.getTimeEntryId()));
            if (entryOptional.isEmpty()) {
                return new TimeEntryAdjustmentResponse(null, false, "Time entry not found");
            }
            com.positivity.people.internal.entity.TimeEntry entry = entryOptional.get();
            if (entry.getStatus() != com.positivity.people.internal.enums.TimeEntryStatus.PENDING_APPROVAL) {
                return new TimeEntryAdjustmentResponse(null, false,
                        "Adjustments can only be created for entries in PENDING_APPROVAL status");
            }
        } catch (Exception invalidTimeEntryId) {
            return new TimeEntryAdjustmentResponse(null, false, "Error validating time entry");
        }

        boolean hasProposedTimes = request.getProposedStartAt() != null || request.getProposedEndAt() != null;
        boolean hasMinutesDelta = request.getMinutesDelta() != null;
        if (!(hasProposedTimes ^ hasMinutesDelta)) {
            return new TimeEntryAdjustmentResponse(null, false,
                    "Provide either both proposedStartAt and proposedEndAt, OR minutesDelta (exactly one)");
        }

        if (hasProposedTimes && (request.getProposedStartAt() == null || request.getProposedEndAt() == null)) {
            return new TimeEntryAdjustmentResponse(null, false,
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

    @Transactional(readOnly = true)
    public List<com.positivity.people.internal.dto.TimeEntryAdjustment> listForTimeEntry(String timeEntryId) {
        return adjustmentRepository.findByTimeEntryId(timeEntryId).stream().map(this::toDto).toList();
    }

    @Transactional
    public boolean approveAdjustment(java.util.UUID adjustmentId, String approverUserId, Set<String> permissions,
            String correlationId) {
        Optional<com.positivity.people.internal.entity.TimeEntryAdjustment> opt = adjustmentRepository
                .findById(adjustmentId);
        if (opt.isEmpty()) {
            return false;
        }
        com.positivity.people.internal.entity.TimeEntryAdjustment adj = opt.get();

        boolean allowed = permissions != null && (permissions.contains("people:timeAdjustment:approve")
                || permissions.contains("admin"));
        if (!allowed) {
            try {
                TimeEntryAudit audit = new TimeEntryAudit();
                audit.setTimeEntryId(adj.getTimeEntryId());
                audit.setAction("ADJUSTMENT_APPROVE_FORBIDDEN");
                audit.setActorId(approverUserId);
                audit.setCorrelationId(correlationId);
                audit.setDetails("Permission denied for adjustment approval");
                auditRepository.save(audit);
            } catch (Exception ignore) {
                // Audit logging failure should not prevent adjustment approval flow.
            }
            return false;
        }

        adj.setStatus(com.positivity.people.internal.enums.AdjustmentStatus.APPROVED);
        adj.setDecidedBy(approverUserId);
        adj.setDecidedAt(Instant.now());
        adjustmentRepository.save(adj);

        try {
            TimeEntryAudit audit = new TimeEntryAudit();
            audit.setTimeEntryId(adj.getTimeEntryId());
            audit.setAction("ADJUSTMENT_APPROVED");
            audit.setActorId(approverUserId);
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
