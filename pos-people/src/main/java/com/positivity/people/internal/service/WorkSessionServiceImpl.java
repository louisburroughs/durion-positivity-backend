package com.positivity.people.internal.service;

import com.positivity.people.internal.entity.WorkSession;
import com.positivity.people.internal.entity.WorkSessionBreak;
import com.positivity.people.internal.repository.WorkSessionBreakRepository;
import com.positivity.people.internal.repository.WorkSessionRepository;
import com.positivity.people.service.BreakDto;
import com.positivity.people.service.WorkSessionDto;
import com.positivity.people.service.WorkSessionNotFoundException;
import com.positivity.people.service.WorkSessionService;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkSessionServiceImpl implements WorkSessionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ENDED = "ENDED";
    private static final String ACTOR_MUST_NOT_BE_NULL = "actor must not be null";

    private final WorkSessionRepository workSessionRepository;
    private final WorkSessionBreakRepository workSessionBreakRepository;

    public WorkSessionServiceImpl(
            WorkSessionRepository workSessionRepository,
            WorkSessionBreakRepository workSessionBreakRepository) {
        this.workSessionRepository = Objects.requireNonNull(workSessionRepository, "workSessionRepository must not be null");
        this.workSessionBreakRepository = Objects.requireNonNull(
                workSessionBreakRepository, "workSessionBreakRepository must not be null");
    }

    @Override
    public WorkSessionDto startSession(@NonNull String personId, @NonNull String actor) {
        Objects.requireNonNull(personId, "personId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        if (workSessionRepository.findByPersonIdAndEndedAtIsNull(personId).isPresent()) {
            throw new IllegalStateException("An active session already exists for personId=" + personId);
        }

        WorkSession session = new WorkSession();
        session.setPersonId(personId);
        session.setStatus(STATUS_ACTIVE);
        session.setStartedAt(Instant.now());
        session.setEndedAt(null);
        session.setActor(actor);

        WorkSession saved = workSessionRepository.save(session);
        return toWorkSessionDto(saved);
    }

    @Override
    public WorkSessionDto stopSession(@NonNull String personId, @NonNull String actor) {
        Objects.requireNonNull(personId, "personId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSession session = workSessionRepository.findByPersonIdAndEndedAtIsNull(personId)
                .orElseThrow(() -> new WorkSessionNotFoundException("No active session found for personId=" + personId));

        Instant endedAt = Instant.now();
        session.setStatus(STATUS_ENDED);
        session.setEndedAt(endedAt);
        session.setActor(actor);
        WorkSession savedSession = workSessionRepository.save(session);

        workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(savedSession.getSessionId())
                .ifPresent(activeBreak -> {
                    activeBreak.setEndedAt(endedAt);
                    activeBreak.setActor(actor);
                    workSessionBreakRepository.save(activeBreak);
                });

        return toWorkSessionDto(savedSession);
    }

    @Override
    public BreakDto startBreak(@NonNull Long sessionId, @NonNull String actor) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSession session = workSessionRepository.findBySessionIdAndEndedAtIsNull(sessionId)
                .orElseThrow(() -> new WorkSessionNotFoundException("No active work session found for sessionId=" + sessionId));

        if (workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(session.getSessionId()).isPresent()) {
            throw new IllegalStateException("A break is already active for sessionId=" + sessionId);
        }

        WorkSessionBreak breakRecord = new WorkSessionBreak();
        breakRecord.setSessionId(sessionId);
        breakRecord.setStartedAt(Instant.now());
        breakRecord.setEndedAt(null);
        breakRecord.setActor(actor);

        WorkSessionBreak saved = workSessionBreakRepository.save(breakRecord);
        return toBreakDto(saved);
    }

    @Override
    public BreakDto stopBreak(@NonNull Long sessionId, @NonNull String actor) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSessionBreak activeBreak = workSessionBreakRepository.findBySessionIdAndEndedAtIsNull(sessionId)
                .orElseThrow(() -> new IllegalStateException("No active break found for sessionId=" + sessionId));

        activeBreak.setEndedAt(Instant.now());
        activeBreak.setActor(actor);
        WorkSessionBreak saved = workSessionBreakRepository.save(activeBreak);
        return toBreakDto(saved);
    }

    private WorkSessionDto toWorkSessionDto(WorkSession session) {
        WorkSessionDto dto = new WorkSessionDto();
        dto.setSessionId(session.getSessionId());
        dto.setPersonId(session.getPersonId());
        dto.setStatus(session.getStatus());
        dto.setStartedAt(session.getStartedAt());
        dto.setEndedAt(session.getEndedAt());
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
