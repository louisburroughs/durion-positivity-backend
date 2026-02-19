package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;

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
