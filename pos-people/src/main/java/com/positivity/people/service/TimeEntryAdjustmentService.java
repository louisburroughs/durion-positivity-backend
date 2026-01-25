package com.positivity.people.service;

import com.positivity.people.entity.TimeEntryAdjustment;
import com.positivity.people.entity.TimeEntryAudit;
import com.positivity.people.repository.TimeEntryAdjustmentRepository;
import com.positivity.people.repository.TimeEntryAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class TimeEntryAdjustmentService {

    private final TimeEntryAdjustmentRepository adjustmentRepository;
    private final TimeEntryAuditRepository auditRepository;

    public TimeEntryAdjustmentService(TimeEntryAdjustmentRepository adjustmentRepository,
            TimeEntryAuditRepository auditRepository) {
        this.adjustmentRepository = adjustmentRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public boolean approveAdjustment(java.util.UUID adjustmentId, String approverUserId, Set<String> permissions,
            String correlationId) {
        Optional<TimeEntryAdjustment> opt = adjustmentRepository.findById(adjustmentId);
        if (opt.isEmpty()) {
            return false;
        }
        TimeEntryAdjustment adj = opt.get();

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
            }
            return false;
        }

        adj.setStatus(com.positivity.people.model.AdjustmentStatus.APPROVED);
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
        }

        return true;
    }
}
