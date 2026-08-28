package com.positivity.workorder.internal.service;

import com.positivity.workorder.internal.dto.RejectTimeEntryRequest;
import com.positivity.workorder.internal.dto.TimeEntryResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TimeEntryService {

    /** Approve a time entry in SUBMITTED state. */
    @NonNull
    TimeEntryResponse approveTimeEntry(@NonNull UUID timeEntryId);

    /** Reject a time entry in SUBMITTED state with a mandatory reason. */
    @NonNull
    TimeEntryResponse rejectTimeEntry(@NonNull UUID timeEntryId, @NonNull RejectTimeEntryRequest request);
}
