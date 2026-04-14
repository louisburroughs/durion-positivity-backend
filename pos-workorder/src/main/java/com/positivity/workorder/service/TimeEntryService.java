package com.positivity.workorder.service;

import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.entity.TimeEntry;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TimeEntryService {

    /** Approve a time entry in SUBMITTED state. */
    @NonNull
    TimeEntry approveTimeEntry(@NonNull UUID timeEntryId);

    /** Reject a time entry in SUBMITTED state with a mandatory reason. */
    @NonNull
    TimeEntry rejectTimeEntry(@NonNull UUID timeEntryId, @NonNull RejectTimeEntryRequest request);
}
