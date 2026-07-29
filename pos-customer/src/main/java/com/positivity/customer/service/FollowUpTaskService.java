package com.positivity.customer.service;

import com.positivity.customer.internal.dto.CloseFollowUpTaskRequest;
import com.positivity.customer.internal.dto.CreateFollowUpTaskRequest;
import com.positivity.customer.internal.dto.FollowUpTaskResponse;
import com.positivity.customer.internal.dto.PagedResponse;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Public API for customer follow-up tasks (Story #1153).
 *
 * <p>Declined work and service-due reminders otherwise live as a note on a closed workorder
 * that nobody revisits; this gives them an owner, a due date, and a recorded outcome.
 *
 * <p>Automatic creation from workorder facts arrives with FI-3 (#1133). Everything here works
 * without it — a CSR can raise, assign, and close tasks today.
 */
public interface FollowUpTaskService {

    @NonNull
    FollowUpTaskResponse create(@NonNull UUID partyId, @NonNull CreateFollowUpTaskRequest request);

    @NonNull
    FollowUpTaskResponse get(@NonNull UUID taskId);

    @NonNull
    PagedResponse<FollowUpTaskResponse> listForParty(
            @NonNull UUID partyId, @Nullable FollowUpStatus status, int page, int size);

    /** The CSR work queue; every filter is optional. */
    @NonNull
    PagedResponse<FollowUpTaskResponse> queue(
            @Nullable FollowUpStatus status,
            @Nullable String assignedTo,
            @Nullable FollowUpType type,
            int page,
            int size);

    @NonNull
    FollowUpTaskResponse assign(@NonNull UUID taskId, @Nullable String assignedTo);

    /** Close as worked, recording the outcome and any booking the hand-off produced. */
    @NonNull
    FollowUpTaskResponse complete(@NonNull UUID taskId, @NonNull CloseFollowUpTaskRequest request);

    /** Close as deliberately not pursued. Distinct from completion for queue reporting. */
    @NonNull
    FollowUpTaskResponse dismiss(@NonNull UUID taskId, @NonNull CloseFollowUpTaskRequest request);
}
