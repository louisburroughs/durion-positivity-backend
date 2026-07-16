package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.PartReturnCreateRequest;
import com.positivity.warranty.internal.dto.PartReturnResponse;
import com.positivity.warranty.internal.dto.PartReturnUpdateRequest;
import com.positivity.warranty.internal.enums.PartReturnStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Defective-part RMA child lifecycle (PRD §3.8). The physical hold shelf stays manual in v1;
 * the {@code PartReturn} record is the system of record. Illegal lifecycle moves surface as
 * {@code IllegalClaimStateException} → 409 {@code ApiError} with {@code nextAction}.
 */
public interface PartReturnService {

    /**
     * Open the RMA for one claim line (policy {@code requiresPartReturn} or staff choice); at
     * most one per line, starting in {@code AWAITING_PART}.
     */
    @NonNull
    PartReturnResponse create(@NonNull UUID claimId, @NonNull PartReturnCreateRequest request);

    /**
     * Advance ({@code AWAITING_PART → ON_HOLD → SHIPPED → RECEIVED_BY_VENDOR}, or to
     * {@code SCRAPPED}/{@code CLOSED} per disposition) or annotate a part return.
     */
    @NonNull
    PartReturnResponse update(@NonNull UUID partReturnId, @NonNull PartReturnUpdateRequest request);

    /** Hold-shelf / shipping worklist (PRD §8); filter optional. */
    @NonNull
    List<PartReturnResponse> worklist(@Nullable PartReturnStatus status);
}
