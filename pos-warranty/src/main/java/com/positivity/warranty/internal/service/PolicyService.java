package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.PolicyRequest;
import com.positivity.warranty.internal.dto.PolicyResponse;
import com.positivity.warranty.internal.enums.CoverageType;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/** Warranty policy CRUD and applicable-policy lookup (PRD §3.2, §8 rows 2-3). */
public interface PolicyService {

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when the provider is unknown */
    @NonNull
    PolicyResponse create(@NonNull PolicyRequest request);

    /**
     * @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id}
     *     or the referenced provider is unknown
     */
    @NonNull
    PolicyResponse update(@NonNull UUID id, @NonNull PolicyRequest request);

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id} is unknown */
    @NonNull
    PolicyResponse getById(@NonNull UUID id);

    /** Lists policies; both filters optional (null = no constraint). */
    @NonNull
    List<PolicyResponse> list(@Nullable UUID providerId, @Nullable CoverageType coverageType);

    /**
     * Policies in effect on {@code saleDate} whose {@code appliesTo} matches ANY provided key,
     * ordered most-specific-first: PRODUCT_LIST &gt; MANUFACTURER &gt; CATEGORY &gt; ALL (PRD §6
     * step 1). {@code ALL}-scoped policies always match; scoped policies match only when the
     * corresponding key is provided and equal.
     */
    @NonNull
    List<PolicyResponse> findApplicable(
            @Nullable UUID productEntityId,
            @Nullable UUID manufacturerId,
            @Nullable UUID categoryId,
            @NonNull LocalDate saleDate,
            @Nullable CoverageType coverageType);
}
