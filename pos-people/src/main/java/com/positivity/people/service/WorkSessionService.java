package com.positivity.people.service;

import org.jspecify.annotations.NonNull;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import java.util.UUID;

public interface WorkSessionService {

    @NonNull
    WorkSessionDto startSession(@NonNull UUID personId);

    @NonNull
    WorkSessionDto stopSession(@NonNull UUID personId);

    @NonNull
    BreakDto startBreak(@NonNull UUID sessionId);

    @NonNull
    BreakDto stopBreak(@NonNull UUID sessionId);
}
