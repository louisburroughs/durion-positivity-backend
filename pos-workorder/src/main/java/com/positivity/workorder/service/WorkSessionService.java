package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.AddBreakSegmentRequest;
import com.positivity.workorder.internal.dto.BreakSegmentResponse;
import com.positivity.workorder.internal.dto.StartWorkSessionRequest;
import com.positivity.workorder.internal.dto.StopWorkSessionRequest;
import com.positivity.workorder.internal.dto.WorkSessionResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface WorkSessionService {

    /**
     * Start a new work session for a mechanic on a work order task.
     * Enforces overlap policy (default DENY unless override conditions met).
     *
     * @param request the session start request
     * @return the created work session response
     */
    @NonNull
    WorkSessionResponse startSession(@NonNull StartWorkSessionRequest request);

    /**
     * Stop an in-progress work session.
     *
     * @param workSessionId the session to stop
     * @param request       optional stop parameters
     * @return the updated work session response
     */
    @NonNull
    WorkSessionResponse stopSession(@NonNull UUID workSessionId, @NonNull StopWorkSessionRequest request);

    /**
     * Add a break segment to an in-progress work session.
     *
     * @param workSessionId the session to add break to
     * @param request       the break details
     * @return the created break segment response
     */
    @NonNull
    BreakSegmentResponse addBreakSegment(@NonNull UUID workSessionId, @NonNull AddBreakSegmentRequest request);

    /**
     * Stop an open break segment on a work session.
     *
     * @param workSessionId  the session containing the break
     * @param breakSegmentId the break segment to stop
     * @return the updated break segment response
     */
    @NonNull
    BreakSegmentResponse stopBreakSegment(@NonNull UUID workSessionId, @NonNull UUID breakSegmentId);
}
