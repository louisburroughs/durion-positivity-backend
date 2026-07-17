package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.ProviderRequest;
import com.positivity.warranty.internal.dto.ProviderResponse;
import com.positivity.warranty.internal.enums.ProviderType;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Warranty provider CRUD (PRD §3.1, §8 row 1). */
public interface ProviderService {

    @NonNull
    ProviderResponse create(@NonNull ProviderRequest request);

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id} is unknown */
    @NonNull
    ProviderResponse update(@NonNull UUID id, @NonNull ProviderRequest request);

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id} is unknown */
    @NonNull
    ProviderResponse getById(@NonNull UUID id);

    /** Lists providers; both filters optional (null = no constraint). */
    @NonNull
    List<ProviderResponse> list(@Nullable String status, @Nullable ProviderType providerType);
}
