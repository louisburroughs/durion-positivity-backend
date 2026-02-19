package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

public interface WorkSessionService {

    @NonNull
    WorkSessionDto startSession(@NonNull String personId, @NonNull String actor);

    @NonNull
    WorkSessionDto stopSession(@NonNull String personId, @NonNull String actor);

    @NonNull
    BreakDto startBreak(@NonNull Long sessionId, @NonNull String actor);

    @NonNull
    BreakDto stopBreak(@NonNull Long sessionId, @NonNull String actor);
}