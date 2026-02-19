package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;

public interface TimeEntryService {

    @NonNull
    List<TimeEntryDecisionResult> approveEntries(
            List<String> timeEntryIds,
            String approverUserId,
            Set<String> permissions,
            String correlationId);

    @NonNull
    List<TimeEntryDecisionResult> rejectEntries(
            List<String> timeEntryIds,
            String rejectorUserId,
            Map<String, String> rejectionReasons,
            Set<String> permissions,
            String correlationId);
}
