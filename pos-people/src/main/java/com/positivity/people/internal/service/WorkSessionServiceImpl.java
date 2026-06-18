package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.exception.WorkSessionNotFoundException;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import com.positivity.people.service.WorkSessionService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkSessionServiceImpl implements WorkSessionService {

    private final Clock clock;

    private static final String STATUS_ACTIVE = "ACTIVE";

    private static final String STATUS_ENDED = "ENDED";

    private static final String STATUS_SUBMITTED = "SUBMITTED";

    private static final String SYSTEM_USER = "system";

    private final WorkSessionRepository workSessionRepository;

    private final WorkSessionBreakRepository workSessionBreakRepository;

    private final PersonRepository personRepository;

    public WorkSessionServiceImpl(
            WorkSessionRepository workSessionRepository,
            WorkSessionBreakRepository workSessionBreakRepository,
            PersonRepository personRepository,
            Clock clock) {
        this.clock = clock;
        this.workSessionRepository =
                Objects.requireNonNull(workSessionRepository, "workSessionRepository must not be null");
        this.workSessionBreakRepository =
                Objects.requireNonNull(workSessionBreakRepository, "workSessionBreakRepository must not be null");
        this.personRepository = Objects.requireNonNull(personRepository, "personRepository must not be null");
    }

    @Override
    public WorkSessionDto startSession(@NonNull UUID personId) {
        Objects.requireNonNull(personId, "personId must not be null");
        String resolvedActor = resolveActorFromSecurityContext();

        if (workSessionRepository.findByPerson_IdAndEndedAtIsNull(personId).isPresent()) {
            throw new IllegalStateException("An active session already exists for personId=" + personId);
        }

        WorkSession session = new WorkSession();
        session.setPerson(personRepository.getReferenceById(personId));
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
                .findByPerson_IdAndEndedAtIsNull(personId)
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
        Objects.requireNonNull(sessionId, "sessionId must not be null");
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
        Objects.requireNonNull(sessionId, "sessionId must not be null");
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
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        String resolvedActor = resolveActorFromSecurityContext();

        WorkSession session = workSessionRepository
                .findById(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException("No work session found for sessionId=" + sessionId));

        if (!STATUS_ENDED.equals(session.getStatus())) {
            throw new IllegalStateException(
                    "Only an ENDED session can be submitted; sessionId=" + sessionId + " status=" + session.getStatus());
        }

        session.setStatus(STATUS_SUBMITTED);
        session.setBillableMinutes(request.getBillableMinutes());
        session.setBreakMinutes(request.getBreakMinutes());
        session.setSubmittedAt(request.getSubmittedAt());
        session.setActor(resolvedActor);

        return toWorkSessionDto(workSessionRepository.save(session));
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
