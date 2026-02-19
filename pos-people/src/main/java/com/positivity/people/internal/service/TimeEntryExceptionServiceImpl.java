package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.service.TimeEntryExceptionService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeEntryExceptionServiceImpl implements TimeEntryExceptionService {

    private final TimeEntryExceptionRepository exceptionRepository;
    private final TimeEntryAuditRepository auditRepository;

    public TimeEntryExceptionServiceImpl(TimeEntryExceptionRepository exceptionRepository,
            TimeEntryAuditRepository auditRepository) {
        this.exceptionRepository = exceptionRepository;
        this.auditRepository = auditRepository;
    }

    @Override
    @Transactional
    public TimeEntryExceptionResponse createException(TimeEntryExceptionRequest request) {
        com.positivity.people.internal.entity.TimeEntryException exception = new com.positivity.people.internal.entity.TimeEntryException();
        exception.setEmployeeId(request.getEmployeeId());
        exception.setExceptionCode(request.getExceptionCode());
        if (request.getSeverity() != null) {
            try {
                exception.setSeverity(
                        com.positivity.people.internal.enums.ExceptionSeverity.valueOf(request.getSeverity()));
            } catch (IllegalArgumentException invalidSeverity) {
                exception.setSeverity(com.positivity.people.internal.enums.ExceptionSeverity.WARNING);
            }
        }
        exception.setTimeEntryId(request.getTimeEntryId());
        exception.setResolutionNotes(request.getResolutionNotes());
        if (request.getDetectedAt() != null) {
            exception.setDetectedAt(request.getDetectedAt().toInstant());
        } else {
            exception.setDetectedAt(Instant.now());
        }
        exception.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

        com.positivity.people.internal.entity.TimeEntryException saved = exceptionRepository.save(exception);
        return new TimeEntryExceptionResponse(saved.getExceptionId(), true, "created");
    }

    @Override
    @Transactional(readOnly = true)
    public List<TimeEntryException> listByEmployee(String employeeId) {
        List<com.positivity.people.internal.entity.TimeEntryException> exceptions = employeeId == null
                ? exceptionRepository.findAll()
                : exceptionRepository.findByEmployeeId(employeeId);
        return exceptions.stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public boolean actionException(java.util.UUID exceptionId,
            com.positivity.people.internal.enums.ExceptionStatus targetStatus,
            String actionUserId, String actionNotes, String correlationId) {
        Optional<com.positivity.people.internal.entity.TimeEntryException> opt = exceptionRepository
                .findById(exceptionId);
        if (opt.isEmpty()) {
            return false;
        }
        com.positivity.people.internal.entity.TimeEntryException ex = opt.get();

        // Validate transition is allowed
        if (ex.getStatus() == com.positivity.people.internal.enums.ExceptionStatus.RESOLVED ||
                ex.getStatus() == com.positivity.people.internal.enums.ExceptionStatus.WAIVED) {
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
            // Audit logging failure should not prevent exception action from succeeding
        }

        return true;
    }

    @Override
    @Transactional
    public boolean resolveException(java.util.UUID exceptionId, String resolverUserId, Set<String> permissions,
            String resolutionNotes, String resolutionAction, String correlationId) {
        Optional<com.positivity.people.internal.entity.TimeEntryException> opt = exceptionRepository
                .findById(exceptionId);
        if (opt.isEmpty()) {
            return false;
        }
        com.positivity.people.internal.entity.TimeEntryException ex = opt.get();

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
                // Audit logging failure should not prevent exception action from succeeding
            }
            return false;
        }

        try {
            if (resolutionAction != null) {
                ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.valueOf(resolutionAction));
            } else {
                ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);
            }
        } catch (IllegalArgumentException iae) {
            ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);
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
            // Audit logging failure should not prevent exception action from succeeding
        }

        return true;
    }

    private TimeEntryException toDto(com.positivity.people.internal.entity.TimeEntryException exception) {
        TimeEntryException dto = new TimeEntryException();
        dto.setExceptionId(exception.getExceptionId());
        dto.setEmployeeId(exception.getEmployeeId());
        dto.setWorkDate(exception.getWorkDate());
        dto.setExceptionCode(exception.getExceptionCode());
        dto.setSeverity(exception.getSeverity());
        dto.setStatus(exception.getStatus());
        dto.setTimeEntryId(exception.getTimeEntryId());
        dto.setResolutionNotes(exception.getResolutionNotes());
        dto.setDetectedAt(exception.getDetectedAt());
        dto.setResolvedBy(exception.getResolvedBy());
        dto.setResolvedAt(exception.getResolvedAt());
        return dto;
    }
}
