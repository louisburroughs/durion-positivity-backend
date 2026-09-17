package com.positivity.people.internal.service;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionClockStateResponse;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface WorkSessionService {

    @NonNull
    WorkSessionDto startSession(@NonNull UUID personId);

    @NonNull
    WorkSessionDto stopSession(@NonNull UUID personId);

    @NonNull
    BreakDto startBreak(@NonNull UUID sessionId);

    @NonNull
    BreakDto stopBreak(@NonNull UUID sessionId);

    @NonNull
    WorkSessionDto submitSession(@NonNull UUID sessionId, @NonNull WorkSessionSubmitRequest request);

    /**
     * One person's current clock state, read-only (issue #2061). {@code CLOCKED_OUT} when nothing
     * is open — never a 404 for that.
     *
     * @param personId the person, or {@code null} for the caller's own linked person
     * @throws com.positivity.people.internal.exception.PersonNotFoundException when the person
     *     is unknown to the identity replica
     * @throws jakarta.persistence.EntityNotFoundException when {@code personId} is omitted and
     *     the caller has no linked person
     * @throws org.springframework.security.access.AccessDeniedException when the caller is
     *     neither the person nor a holder of {@code people:timekeeping:view} covering them
     */
    @NonNull
    WorkSessionClockStateResponse getCurrentClockState(@Nullable UUID personId);

    /**
     * The current clock state of every given person, resolved in a bounded number of queries
     * (issue #2061, BR7), keyed by person id with an entry for every id asked for. Read-only and
     * unauthorized: the caller decides who may see which entry.
     */
    @NonNull
    Map<UUID, WorkSessionClockStateResponse> resolveClockStates(@NonNull Collection<UUID> personIds);
}
