package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.RegistrationRequest;
import com.positivity.warranty.internal.dto.RegistrationResponse;
import com.positivity.warranty.internal.enums.RegistrationStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Sold-coverage (registration) CRUD and lookup (PRD §3.3, §8 row 4). */
public interface RegistrationService {

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when the policy is unknown */
    @NonNull
    RegistrationResponse create(@NonNull RegistrationRequest request);

    /**
     * @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id}
     *     or the referenced policy is unknown
     */
    @NonNull
    RegistrationResponse update(@NonNull UUID id, @NonNull RegistrationRequest request);

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id} is unknown */
    @NonNull
    RegistrationResponse getById(@NonNull UUID id);

    /** Lists registrations; all filters optional (null = no constraint). */
    @NonNull
    List<RegistrationResponse> search(
            @Nullable UUID customerId, @Nullable UUID vehicleId, @Nullable RegistrationStatus status);
}
