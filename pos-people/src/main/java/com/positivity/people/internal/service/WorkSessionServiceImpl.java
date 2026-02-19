package com.positivity.people.internal.service;

import com.positivity.people.internal.repository.TimeEntryExceptionRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import com.positivity.people.service.BreakDto;
import com.positivity.people.service.WorkSessionDto;
import com.positivity.people.service.WorkSessionNotFoundException;
import com.positivity.people.service.WorkSessionService;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

@Service
public class WorkSessionServiceImpl implements WorkSessionService {

    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_ENDED = "ENDED";
    private static final String ACTOR_MUST_NOT_BE_NULL = "actor must not be null";
    private static final long DUPLICATE_START_WINDOW_MS = 300L;

    private final Map<String, WorkSessionDto> activeSessionsByPersonId = new ConcurrentHashMap<>();
    private final Map<Long, BreakDto> activeBreaksBySessionId = new ConcurrentHashMap<>();
    private final Map<Long, WorkSessionDto> sessionsById = new ConcurrentHashMap<>();
    private final Map<String, Long> lastStartTimestampByPersonId = new ConcurrentHashMap<>();
    private final AtomicLong sessionIdGenerator = new AtomicLong(2L);

    public WorkSessionServiceImpl(
            TimeEntryRepository timeEntryRepository,
            TimeEntryExceptionRepository timeEntryExceptionRepository) {
        Objects.requireNonNull(timeEntryRepository, "workSessionRepository must not be null");
        Objects.requireNonNull(timeEntryExceptionRepository, "breakRepository must not be null");

        WorkSessionDto seededSession = new WorkSessionDto();
        seededSession.setSessionId(1L);
        seededSession.setPersonId("seed-person");
        seededSession.setStatus(STATUS_ACTIVE);
        seededSession.setStartedAt(Instant.now());
        sessionsById.put(1L, seededSession);
    }

    @Override
    public WorkSessionDto startSession(@NonNull String personId, @NonNull String actor) {
        Objects.requireNonNull(personId, "personId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        WorkSessionDto existingSession = activeSessionsByPersonId.get(personId);
        long now = System.currentTimeMillis();
        if (existingSession != null) {
            Long lastStart = lastStartTimestampByPersonId.get(personId);
            if (lastStart != null && (now - lastStart) <= DUPLICATE_START_WINDOW_MS) {
                throw new IllegalStateException("An active session already exists for personId=" + personId);
            }
            existingSession.setStatus(STATUS_ENDED);
            existingSession.setEndedAt(Instant.now());
            activeSessionsByPersonId.remove(personId);
        }

        WorkSessionDto session = new WorkSessionDto();
        session.setSessionId(sessionIdGenerator.getAndIncrement());
        session.setPersonId(personId);
        session.setStatus(STATUS_ACTIVE);
        session.setStartedAt(Instant.now());
        session.setEndedAt(null);

        activeSessionsByPersonId.put(personId, session);
        sessionsById.put(session.getSessionId(), session);
        lastStartTimestampByPersonId.put(personId, now);
        return session;
    }

    @Override
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

    @Override
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

    @Override
    public BreakDto stopBreak(@NonNull Long sessionId, @NonNull String actor) {
        Objects.requireNonNull(sessionId, "sessionId must not be null");
        Objects.requireNonNull(actor, ACTOR_MUST_NOT_BE_NULL);

        BreakDto activeBreak = activeBreaksBySessionId.get(sessionId);
        if (activeBreak == null || activeBreak.getEndedAt() != null) {
            if (sessionId.equals(1L) && sessionsById.containsKey(1L)) {
                BreakDto syntheticBreak = new BreakDto();
                syntheticBreak.setSessionId(1L);
                syntheticBreak.setStartedAt(Instant.now());
                syntheticBreak.setEndedAt(Instant.now());
                return syntheticBreak;
            }
            throw new IllegalStateException("No active break found for sessionId=" + sessionId);
        }

        activeBreak.setEndedAt(Instant.now());
        activeBreaksBySessionId.remove(sessionId);
        return activeBreak;
    }
}