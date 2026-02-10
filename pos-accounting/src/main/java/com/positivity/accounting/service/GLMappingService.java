package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.GLMappingCreateRequest;
import com.positivity.accounting.internal.dto.GLMappingCreateResponse;
import com.positivity.accounting.internal.dto.GLMappingResolveRequest;
import com.positivity.accounting.internal.dto.GLMappingResolveResponse;
import org.jspecify.annotations.NonNull;

/**
 * Service interface for GL mapping management.
 * Provides operations for creating and resolving GL mappings between external
 * codes and GL accounts.
 */
public interface GLMappingService {

    /**
     * Creates a new GL mapping from external source system code to GL account.
     * Validates that the mapping does not overlap with existing mappings.
     *
     * @param request mapping creation request
     * @return created mapping response
     * @throws IllegalArgumentException if validation fails or overlapping mapping
     *                                  exists
     */
    @NonNull
    GLMappingCreateResponse createMapping(@NonNull GLMappingCreateRequest request);

    /**
     * Resolves an external code to a GL account using temporal matching.
     * Finds the effective mapping at the transaction date.
     *
     * @param request resolve request with source system, external code, and
     *                transaction date
     * @return GL account ID
     * @throws IllegalArgumentException if no effective mapping found
     */
    @NonNull
    GLMappingResolveResponse resolveMapping(@NonNull GLMappingResolveRequest request);
}
