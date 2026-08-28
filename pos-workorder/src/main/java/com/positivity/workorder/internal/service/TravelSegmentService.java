package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.dto.TravelSegmentAdjustmentResponse;
import com.positivity.workorder.internal.dto.TravelSegmentResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TravelSegmentService {

    /**
     * Start a new travel segment for a mobile work assignment.
     */
    @NonNull
    TravelSegmentResponse startTravelSegment(@NonNull StartTravelSegmentRequest request);

    /**
     * Stop an in-progress travel segment.
     */
    @NonNull
    TravelSegmentResponse stopTravelSegment(@NonNull UUID travelSegmentId, @NonNull StopTravelSegmentRequest request);

    /**
     * Submit all travel segments for a given mobile work assignment.
     */
    @NonNull
    List<TravelSegmentResponse> submitTravelSegments(@NonNull UUID mobileWorkAssignmentId);

    /**
     * Create a post-approval adjustment for an approved travel segment.
     */
    @NonNull
    TravelSegmentAdjustmentResponse createAdjustment(
            @NonNull UUID travelSegmentId, @NonNull CreateTravelSegmentAdjustmentRequest request);
}
