package com.positivity.people.service;

import com.positivity.people.internal.dto.BreakDto;
import com.positivity.people.internal.dto.WorkSessionDto;
import com.positivity.people.internal.dto.WorkSessionSubmitRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

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
}
