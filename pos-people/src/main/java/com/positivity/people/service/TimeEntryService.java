package com.positivity.people.service;

import com.positivity.people.internal.dto.TimeEntryDecisionResult;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public interface TimeEntryService {

    @NonNull
    List<TimeEntryDecisionResult> approveEntries(List<String> timeEntryIds, String correlationId);

    @NonNull
    List<TimeEntryDecisionResult> rejectEntries(
            List<String> timeEntryIds, Map<String, String> rejectionReasons, String correlationId);
}
