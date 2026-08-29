package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkSessionServiceImpl implements WorkSessionService {
    private static final String SESSION_ID_REQUIRED = "sessionId must not be null";

    private final Clock clock;

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_ENDED = "ENDED";

    private static final String STATUS_SUBMITTED = "SUBMITTED";

    private static final String SYSTEM_USER = "system";

    private final WorkSessionRepository workSessionRepository;

    private final WorkSessionBreakRepository workSessionBreakRepository;

    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    private final TimeEntryRepository timeEntryRepository;

    private final EmployeeLocationAssignmentRepository locationAssignmentRepository;

    public WorkSessionServiceImpl(
            WorkSessionRepository workSessionRepository,
            WorkSessionBreakRepository workSessionBreakRepository,
            ExtPersonReplicaRepository extPersonReplicaRepository,
            TimeEntryRepository timeEntryRepository,
            EmployeeLocationAssignmentRepository locationAssignmentRepository,
            Clock clock) {
        this.clock = clock;
        this.workSessionRepository =
                Objects.requireNonNull(workSessionRepository, "workSessionRepository must not be null");
        this.workSessionBreakRepository =
                Objects.requireNonNull(workSessionBreakRepository, "workSessionBreakRepository must not be null");
        this.extPersonReplicaRepository =
                Objects.requireNonNull(extPersonReplicaRepository, "extPersonReplicaRepository must not be null");
        this.timeEntryRepository = Objects.requireNonNull(timeEntryRepository, "timeEntryRepository must not be null");
        this.locationAssignmentRepository =
                Objects.requireNonNull(locationAssignmentRepository, "locationAssignmentRepository must not be null");
    }

    @Override
    public WorkSessionDto startSession(@NonNull UUID personId) {
        Objects.requireNonNull(personId, "personId must not be null");
        String resolvedActor = resolveActorFromSecurityContext();

        // The person FK went with the ADR-0044 split (#875), so validate against the identity
        // replica instead — sessions must not be creatable for unknown persons.
        if (!extPersonReplicaRepository.existsById(personId)) {
            throw new PersonNotFoundException(personId);
        }

        if (workSessionRepository.findByPersonIdAndEndedAtIsNull(personId).isPresent()) {
            throw new IllegalStateException("An active session already exists for personId=" + personId);
        }

        WorkSession session = new WorkSession();
        session.setPersonId(personId);
        session.setStatus(STATUS_ACTIVE);
        session.setStartedAt(Instant.now(clock));
        session.setEndedAt(null);
        session.setActor(resolvedActor);

        try {
            WorkSession saved = workSessionRepository.save(session);
            return toWorkSessionDto(saved);
        } catch (DataIntegrityViolationException ex) {
            // Protect against concurrent start requests racing past the pre-check.
            throw new IllegalStateException("An active session already exists for personId=" + personId, ex);
        }
    }

    @Override
    public WorkSessionDto stopSession(@NonNull UUID personId) {
        Objects.requireNonNull(personId, "personId must not be null");
        String resolvedActor = resolveActorFromSecurityContext();

        WorkSession session = workSessionRepository
                .findByPersonIdAndEndedAtIsNull(personId)
                .orElseThrow(
                        () -> new WorkSessionNotFoundException("No active session found for personId=" + personId));

        Instant endedAt = Instant.now(clock);
        session.setStatus(STATUS_ENDED);
        session.setEndedAt(endedAt);
        session.setActor(resolvedActor);
        WorkSession savedSession = workSessionRepository.save(session);

        workSessionBreakRepository
                .findBySession_SessionIdAndEndedAtIsNull(savedSession.getSessionId())
                .ifPresent(activeBreak -> {
                    activeBreak.setEndedAt(endedAt);
                    activeBreak.setActor(resolvedActor);
                    workSessionBreakRepository.save(activeBreak);
                });

        return toWorkSessionDto(savedSession);
    }

    @Override
    public BreakDto startBreak(@NonNull UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        String resolvedActor = resolveActorFromSecurityContext();

        WorkSession session = workSessionRepository
                .findBySessionIdAndEndedAtIsNull(sessionId)
                .orElseThrow(() ->
                        new WorkSessionNotFoundException("No active work session found for sessionId=" + sessionId));

        if (workSessionBreakRepository
                .findBySession_SessionIdAndEndedAtIsNull(session.getSessionId())
                .isPresent()) {
            throw new IllegalStateException("A break is already active for sessionId=" + sessionId);
        }

        WorkSessionBreak breakRecord = new WorkSessionBreak();
        breakRecord.setSession(session);
        breakRecord.setStartedAt(Instant.now(clock));
        breakRecord.setEndedAt(null);
        breakRecord.setActor(resolvedActor);

        try {
            WorkSessionBreak saved = workSessionBreakRepository.save(breakRecord);
            return toBreakDto(saved);
        } catch (DataIntegrityViolationException ex) {
            // Protect against concurrent break-start requests racing past the pre-check.
            throw new IllegalStateException("A break is already active for sessionId=" + sessionId, ex);
        }
    }

    @Override
    public BreakDto stopBreak(@NonNull UUID sessionId) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        String resolvedActor = resolveActorFromSecurityContext();

        WorkSessionBreak activeBreak = workSessionBreakRepository
                .findBySession_SessionIdAndEndedAtIsNull(sessionId)
                .orElseThrow(() -> new IllegalStateException("No active break found for sessionId=" + sessionId));

        activeBreak.setEndedAt(Instant.now(clock));
        activeBreak.setActor(resolvedActor);
        WorkSessionBreak saved = workSessionBreakRepository.save(activeBreak);
        return toBreakDto(saved);
    }

    @Override
    public WorkSessionDto submitSession(@NonNull UUID sessionId, @NonNull WorkSessionSubmitRequest request) {
        Objects.requireNonNull(sessionId, SESSION_ID_REQUIRED);
        Objects.requireNonNull(request, "request must not be null");
        String resolvedActor = resolveActorFromSecurityContext();

        WorkSession session = workSessionRepository
                .findById(sessionId)
                .orElseThrow(
                        () -> new WorkSessionNotFoundException("No work session found for sessionId=" + sessionId));

        if (!STATUS_ENDED.equals(session.getStatus())) {
            throw new IllegalStateException("Only an ENDED session can be submitted; sessionId=" + sessionId
                    + " status=" + session.getStatus());
        }

        session.setStatus(STATUS_SUBMITTED);
        session.setBillableMinutes(request.getBillableMinutes());
        session.setBreakMinutes(request.getBreakMinutes());
        session.setSubmittedAt(request.getSubmittedAt());
        session.setActor(resolvedActor);

        WorkSession submitted = workSessionRepository.save(session);
        recordTimeEntry(submitted, request);
        return toWorkSessionDto(submitted);
    }

    /**
     * A submitted session is the employee's time entry (#1564). This is the only producer of
     * {@code time_entry} rows, which the approval, adjustment, exception, and payroll-export
     * surfaces all read; before it existed those surfaces had nothing to act on.
     *
     * <p>The attendance window is the server-stamped session window, so it is gross time.
     * Breaks are carried alongside rather than deducted here, leaving consumers free to report
     * either gross attendance or net worked time.
     */
    private void recordTimeEntry(WorkSession session, WorkSessionSubmitRequest request) {
        TimeEntry entry = new TimeEntry();
        entry.setWorkSessionId(session.getSessionId());
        entry.setPersonId(session.getPersonId());
        entry.setLocationId(resolveLocationId(session));
        entry.setAttendanceStartAt(session.getStartedAt());
        entry.setAttendanceEndAt(session.getEndedAt());
        entry.setBreakMinutes(request.getBreakMinutes());
        entry.setStatus(TimeEntryStatus.SUBMITTED);
        timeEntryRepository.save(entry);
    }

    /**
     * Attendance reporting and the payroll export both filter on location, so an entry without
     * one is invisible to them. Resolve the assignment in effect on the day the session ended;
     * the query orders primary assignments first, so the primary one wins when a person holds
     * several. A person with no active assignment still gets an entry that can be approved.
     */
    private UUID resolveLocationId(WorkSession session) {
        Instant endedAt = session.getEndedAt() == null ? Instant.now(clock) : session.getEndedAt();
        LocalDate onDate = endedAt.atZone(ZoneOffset.UTC).toLocalDate();
        return locationAssignmentRepository.findActiveByPersonIdAndDate(session.getPersonId(), onDate).stream()
                .findFirst()
                .map(EmployeeLocationAssignment::getLocationId)
                .orElse(null);
    }

    private String resolveActorFromSecurityContext() {
        return SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_USER);
    }

    private WorkSessionDto toWorkSessionDto(WorkSession session) {
        WorkSessionDto dto = new WorkSessionDto();
        dto.setSessionId(session.getSessionId());
        dto.setPersonId(session.getPersonId());
        dto.setStatus(session.getStatus());
        dto.setStartedAt(session.getStartedAt());
        dto.setEndedAt(session.getEndedAt());
        dto.setBillableMinutes(session.getBillableMinutes());
        dto.setBreakMinutes(session.getBreakMinutes());
        dto.setSubmittedAt(session.getSubmittedAt());
        return dto;
    }

    private BreakDto toBreakDto(WorkSessionBreak breakRecord) {
        BreakDto dto = new BreakDto();
        dto.setSessionId(breakRecord.getSessionId());
        dto.setStartedAt(breakRecord.getStartedAt());
        dto.setEndedAt(breakRecord.getEndedAt());
        return dto;
    }
}
