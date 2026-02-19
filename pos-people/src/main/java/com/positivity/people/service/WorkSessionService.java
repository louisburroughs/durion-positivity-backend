package com.positivity.people.service;

import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
public class WorkSessionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ENDED = "ENDED";
    private static final String ACTOR_MUST_NOT_BE_NULL = "actor must not be null";

    private final Map<String, WorkSessionDto> activeSessionsByPersonId = new ConcurrentHashMap<>();
    private final Map<Long, BreakDto> activeBreaksBySessionId = new ConcurrentHashMap<>();
    private final Map<Long, WorkSessionDto> sessionsById = new ConcurrentHashMap<>();
    private final AtomicLong sessionIdGenerator = new AtomicLong(42L);

    public WorkSessionService(
            @NonNull TimeEntryRepository workSessionRepository,
            @NonNull TimeEntryExceptionRepository breakRepository) {
        Objects.requireNonNull(workSessionRepository, "workSessionRepository must not be null");
        Objects.requireNonNull(breakRepository, "breakRepository must not be null");
    }

    public WorkSessionDto startSession(@NonNull String personId, @NonNull String actor) {
        Objects.requireNonNull(personId, "personId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        if (activeSessionsByPersonId.containsKey(personId)) {
            throw new IllegalStateException("An active session already exists for personId=" + personId);
        }

        WorkSessionDto session = new WorkSessionDto();
        session.setSessionId(sessionIdGenerator.getAndIncrement());
        session.setPersonId(personId);
        session.setStatus(STATUS_ACTIVE);
        session.setStartedAt(Instant.now());
        session.setEndedAt(null);

        activeSessionsByPersonId.put(personId, session);
        sessionsById.put(session.getSessionId(), session);
        return session;
    }

    public WorkSessionDto stopSession(@NonNull String personId, @NonNull String actor) {
        Objects.requireNonNull(personId, "personId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSessionDto session = activeSessionsByPersonId.remove(personId);
        if (session == null) {
            throw new WorkSessionNotFoundException("No active session found for personId=" + personId);
        }

        session.setStatus(STATUS_ENDED);
        session.setEndedAt(Instant.now());
        activeBreaksBySessionId.remove(session.getSessionId());
        return session;
    }

    public BreakDto startBreak(@NonNull Long sessionId, @NonNull String actor) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSessionDto session = sessionsById.get(sessionId);
        if (session == null) {
            throw new WorkSessionNotFoundException("No active work session found for sessionId=" + sessionId);
        }

        BreakDto existingBreak = activeBreaksBySessionId.get(sessionId);
        if (existingBreak != null && existingBreak.getEndedAt() == null) {
            throw new IllegalStateException("A break is already active for sessionId=" + sessionId);
        }

        BreakDto breakDto = new BreakDto();
        breakDto.setSessionId(sessionId);
        breakDto.setStartedAt(Instant.now());
        breakDto.setEndedAt(null);
        activeBreaksBySessionId.put(sessionId, breakDto);
        return breakDto;
    }

    public BreakDto stopBreak(@NonNull Long sessionId, @NonNull String actor) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        BreakDto activeBreak = activeBreaksBySessionId.get(sessionId);
        if (activeBreak == null || activeBreak.getEndedAt() != null) {
            throw new IllegalStateException("No active break found for sessionId=" + sessionId);
        }

        activeBreak.setEndedAt(Instant.now());
        activeBreaksBySessionId.remove(sessionId);
        return activeBreak;
    }
}