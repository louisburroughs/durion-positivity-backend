package com.positivity.people.internal.service;

import java.time.Clock;

import com.positivity.people.internal.dto.TimeEntryException;
import com.positivity.people.internal.dto.TimeEntryExceptionRequest;
import com.positivity.people.internal.dto.TimeEntryExceptionResponse;
import com.positivity.people.internal.entity.TimeEntryAudit;
import com.positivity.people.internal.enums.ExceptionStatus;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.service.TimeEntryExceptionService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TimeEntryExceptionServiceImpl implements TimeEntryExceptionService {

	private final Clock clock;

	private final TimeEntryExceptionRepository exceptionRepository;

	private final TimeEntryAuditRepository auditRepository;

	public TimeEntryExceptionServiceImpl(TimeEntryExceptionRepository exceptionRepository,
			TimeEntryAuditRepository auditRepository, Clock clock) {
		this.clock = clock;
		this.exceptionRepository = exceptionRepository;
		this.auditRepository = auditRepository;
	}

	@Override
	@Transactional
	@NonNull public TimeEntryExceptionResponse createException(@NonNull TimeEntryExceptionRequest request) {
		com.positivity.people.internal.entity.TimeEntryException exception = new com.positivity.people.internal.entity.TimeEntryException();
		exception.setEmployeeId(request.getEmployeeId());
		exception.setExceptionCode(request.getExceptionCode());
		if (request.getSeverity() != null) {
			try {
				exception
					.setSeverity(com.positivity.people.internal.enums.ExceptionSeverity.valueOf(request.getSeverity()));
			}
			catch (IllegalArgumentException invalidSeverity) {
				exception.setSeverity(com.positivity.people.internal.enums.ExceptionSeverity.WARNING);
			}
		}
		exception.setTimeEntryId(request.getTimeEntryId());
		exception.setResolutionNotes(request.getResolutionNotes());
		if (request.getDetectedAt() != null) {
			exception.setDetectedAt(request.getDetectedAt().toInstant());
		}
		else {
			exception.setDetectedAt(Instant.now(clock));
		}
		exception.setStatus(com.positivity.people.internal.enums.ExceptionStatus.OPEN);

		com.positivity.people.internal.entity.TimeEntryException saved = exceptionRepository.save(exception);
		return new TimeEntryExceptionResponse(saved.getExceptionId(), true, "created");
	}

	@Override
	@Transactional(readOnly = true)
	@NonNull public List<TimeEntryException> listByEmployee(String employeeId) {
		List<com.positivity.people.internal.entity.TimeEntryException> exceptions = employeeId == null
				? exceptionRepository.findAll() : exceptionRepository.findByEmployeeId(employeeId);
		return exceptions.stream().map(this::toDto).toList();
	}

	@Override
	@Transactional
	public void actionException(@NonNull UUID exceptionId, @NonNull ExceptionStatus targetStatus, String actionNotes,
			String correlationId) {
		String actorId = SecurityContextHelper.getCurrentUsername()
			.orElseThrow(() -> new IllegalStateException("No current user"));
		com.positivity.people.internal.entity.TimeEntryException ex = exceptionRepository.findById(exceptionId)
			.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("exception not found"));

		// Validate transition is allowed
		if (ex.getStatus() == com.positivity.people.internal.enums.ExceptionStatus.RESOLVED
				|| ex.getStatus() == com.positivity.people.internal.enums.ExceptionStatus.WAIVED) {
			// Cannot transition from RESOLVED or WAIVED states
			throw new IllegalArgumentException("Cannot modify exception in " + ex.getStatus() + " status");
		}

		// Apply action
		ex.setStatus(targetStatus);
		ex.setResolvedBy(actorId);
		ex.setResolvedAt(Instant.now(clock));
		if (actionNotes != null) {
			ex.setResolutionNotes(actionNotes);
		}
		exceptionRepository.save(ex);

		try {
			TimeEntryAudit audit = new TimeEntryAudit();
			audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
			audit.setAction("EXCEPTION_" + targetStatus.toString());
			audit.setActorId(actorId);
			audit.setCorrelationId(correlationId);
			audit.setDetails("Exception " + targetStatus.toString().toLowerCase() + ": "
					+ (actionNotes != null ? actionNotes : ""));
			auditRepository.save(audit);
		}
		catch (Exception ignore) {
			// Audit logging failure should not prevent exception action from succeeding
		}
	}

	@Override
	@Transactional
	public void resolveException(@NonNull UUID exceptionId, Set<String> permissions, String resolutionNotes,
			String resolutionAction, String correlationId) {
		String actorId = SecurityContextHelper.getCurrentUsername()
			.orElseThrow(() -> new IllegalStateException("No current user"));
		com.positivity.people.internal.entity.TimeEntryException ex = exceptionRepository.findById(exceptionId)
			.orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("exception not found"));

		boolean allowed = permissions != null
				&& (permissions.contains("people:timeException:resolve") || permissions.contains("admin"));
		if (!allowed) {
			try {
				TimeEntryAudit audit = new TimeEntryAudit();
				audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
				audit.setAction("EXCEPTION_RESOLVE_FORBIDDEN");
				audit.setActorId(actorId);
				audit.setCorrelationId(correlationId);
				audit.setDetails("Permission denied for exception resolve");
				auditRepository.save(audit);
			}
			catch (Exception ignore) {
				// Audit logging failure should not prevent exception action from
				// succeeding
			}
			throw new org.springframework.security.access.AccessDeniedException(
					"Permission denied for exception resolve");
		}

		try {
			if (resolutionAction != null) {
				ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.valueOf(resolutionAction));
			}
			else {
				ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);
			}
		}
		catch (IllegalArgumentException iae) {
			ex.setStatus(com.positivity.people.internal.enums.ExceptionStatus.RESOLVED);
		}
		ex.setResolvedBy(actorId);
		ex.setResolvedAt(Instant.now(clock));
		if (resolutionNotes != null) {
			ex.setResolutionNotes(resolutionNotes);
		}
		exceptionRepository.save(ex);

		try {
			TimeEntryAudit audit = new TimeEntryAudit();
			audit.setTimeEntryId(ex.getTimeEntryId() != null ? ex.getTimeEntryId() : "");
			audit.setAction("EXCEPTION_RESOLVED");
			audit.setActorId(actorId);
			audit.setCorrelationId(correlationId);
			audit.setDetails("Exception resolved: " + (resolutionNotes != null ? resolutionNotes : ""));
			auditRepository.save(audit);
		}
		catch (Exception ignore) {
			// Audit logging failure should not prevent exception action from succeeding
		}
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
