package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.CreateTravelSegmentAdjustmentRequest;
import com.positivity.workorder.internal.dto.StartTravelSegmentRequest;
import com.positivity.workorder.internal.dto.StopTravelSegmentRequest;
import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.UUID;

public interface TravelSegmentService {

    /**
     * Start a new travel segment for a mobile work assignment.
     */
    @NonNull
    TravelSegment startTravelSegment(@NonNull StartTravelSegmentRequest request);

    /**
     * Stop an in-progress travel segment.
     */
    @NonNull
    TravelSegment stopTravelSegment(@NonNull UUID travelSegmentId, @NonNull StopTravelSegmentRequest request);

    /**
     * Submit all travel segments for a given mobile work assignment.
     */
    @NonNull
    List<TravelSegment> submitTravelSegments(@NonNull UUID mobileWorkAssignmentId);

    /**
     * Create a post-approval adjustment for an approved travel segment.
     */
    @NonNull
    TravelSegmentAdjustment createAdjustment(@NonNull UUID travelSegmentId,
            @NonNull CreateTravelSegmentAdjustmentRequest request);
}
