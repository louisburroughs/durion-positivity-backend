package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

public interface WorkSessionService {

    @NonNull
    WorkSessionDto startSession(@NonNull String personId);

    @NonNull
    WorkSessionDto stopSession(@NonNull String personId);

    @NonNull
    BreakDto startBreak(@NonNull Long sessionId);

    @NonNull
    BreakDto stopBreak(@NonNull Long sessionId);
}
