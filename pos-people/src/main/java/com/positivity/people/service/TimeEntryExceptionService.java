package com.positivity.people.service;

import com.positivity.people.entity.TimeEntryException;
import com.positivity.people.entity.TimeEntryAudit;
import com.positivity.people.repository.TimeEntryExceptionRepository;
import com.positivity.people.repository.TimeEntryAuditRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;

@Service
public class TimeEntryExceptionService {

    private final TimeEntryExceptionRepository exceptionRepository;
    private final TimeEntryAuditRepository auditRepository;

    public TimeEntryExceptionService(TimeEntryExceptionRepository exceptionRepository,
            TimeEntryAuditRepository auditRepository) {
        this.exceptionRepository = exceptionRepository;
        this.auditRepository = auditRepository;
    }

    @Transactional
    public boolean actionException(java.util.UUID exceptionId, com.positivity.people.model.ExceptionStatus targetStatus,
            String actionUserId, String actionNotes, String correlationId) {
        Optional<TimeEntryException> opt = exceptionRepository.findById(exceptionId);
        if (opt.isEmpty()) {
            return false;
        }
        TimeEntryException ex = opt.get();

        // Validate transition is allowed
        if (ex.getStatus() == com.positivity.people.model.ExceptionStatus.RESOLVED ||
                ex.getStatus() == com.positivity.people.model.ExceptionStatus.WAIVED) {
            // Cannot transition from RESOLVED or WAIVED states
            return false;
        }

        // Apply action
        ex.setStatus(targetStatus);
        ex.setResolvedBy(actionUserId);
        ex.setResolvedAt(Instant.now());
        if (actionNotes != null) {
            ex.setResolutionNotes(actionNotes);
        }
        exceptionRepository.save(ex);

        try {
            TimeEntryAudit audit = new TimeEntryAudit();
            audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
            audit.setAction("EXCEPTION_" + targetStatus.toString());
            audit.setActorId(actionUserId);
            audit.setCorrelationId(correlationId);
            audit.setDetails("Exception " + targetStatus.toString().toLowerCase() + ": "
                    + (actionNotes != null ? actionNotes : ""));
            auditRepository.save(audit);
        } catch (Exception ignore) {
        }

        return true;
    }

    @Transactional
    public boolean resolveException(java.util.UUID exceptionId, String resolverUserId, Set<String> permissions,
            String resolutionNotes, String resolutionAction, String correlationId) {
        Optional<TimeEntryException> opt = exceptionRepository.findById(exceptionId);
        if (opt.isEmpty()) {
            return false;
        }
        TimeEntryException ex = opt.get();

        boolean allowed = permissions != null && (permissions.contains("people:timeException:resolve")
                || permissions.contains("admin"));
        if (!allowed) {
            try {
                TimeEntryAudit audit = new TimeEntryAudit();
                audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
                audit.setAction("EXCEPTION_RESOLVE_FORBIDDEN");
                audit.setActorId(resolverUserId);
                audit.setCorrelationId(correlationId);
                audit.setDetails("Permission denied for exception resolve");
                auditRepository.save(audit);
            } catch (Exception ignore) {
            }
            return false;
        }

        try {
            if (resolutionAction != null) {
                ex.setStatus(com.positivity.people.model.ExceptionStatus.valueOf(resolutionAction));
            } else {
                ex.setStatus(com.positivity.people.model.ExceptionStatus.RESOLVED);
            }
        } catch (IllegalArgumentException iae) {
            ex.setStatus(com.positivity.people.model.ExceptionStatus.RESOLVED);
        }
        ex.setResolvedBy(resolverUserId);
        ex.setResolvedAt(Instant.now());
        if (resolutionNotes != null) {
            ex.setResolutionNotes(resolutionNotes);
        }
        exceptionRepository.save(ex);

        try {
            TimeEntryAudit audit = new TimeEntryAudit();
            audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
            audit.setAction("EXCEPTION_RESOLVED");
            audit.setActorId(resolverUserId);
            audit.setCorrelationId(correlationId);
            audit.setDetails("Exception resolved: " + (resolutionNotes != null ? resolutionNotes : ""));
            auditRepository.save(audit);
        } catch (Exception ignore) {
        }

        return true;
    }
}
