package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.dto.ReadingTierCreateRequest;
import com.positivity.tenant.internal.dto.ReadingTierResponse;
import com.positivity.tenant.internal.dto.ReadingTierUpdateRequest;
import com.positivity.tenant.internal.entity.ReadingTierEntity;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** The reading-tier catalog and tier assignment (CAP:550 S45, #2619; AW63, AW68). Platform operators only. */
public interface ReadingTierService {

    @NonNull
    List<ReadingTierResponse> list();

    /** 409 {@code READING_TIER_EXISTS} when the code is taken. */
    @NonNull
    ReadingTierResponse create(@NonNull ReadingTierCreateRequest request);

    /**
     * Changes the display name, the allowance or the active flag. A changed allowance publishes one {@code
     * tenant.reading-allowance.changed} per non-decommissioned tenant on the tier, in this transaction. Deactivating a
     * tier such a tenant is on is 409 {@code READING_TIER_IN_USE}; 404 for an unknown code.
     */
    @NonNull
    ReadingTierResponse update(@NonNull String code, @NonNull ReadingTierUpdateRequest request);

    /**
     * The tier a tenant may be put on: {@code requestedCode}, or {@code STANDARD} when null. 422 {@code
     * READING_TIER_NOT_ASSIGNABLE} when it does not exist or is inactive.
     */
    @NonNull
    ReadingTierEntity requireAssignable(@Nullable String requestedCode);
}
