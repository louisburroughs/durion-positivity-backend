package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.DefaultGLMappingListResponse;
import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service for managing default GL account mappings for event types.
 * Provides fallback GL accounts when no explicit posting rules exist.
 */
public interface DefaultGLMappingService {

    /**
     * Creates a new default GL mapping.
     *
     * @param request the mapping request to create
     * @return the created mapping response
     */
    @NonNull
    DefaultGLMappingResponse createDefaultMapping(@NonNull DefaultGLMappingRequest request);

    /**
     * Updates an existing default GL mapping.
     *
     * @param mappingId the ID of the mapping to update
     * @param request   the updated mapping data
     * @return the updated mapping response
     * @throws IllegalArgumentException if mapping not found
     */
    @NonNull
    DefaultGLMappingResponse updateDefaultMapping(@NonNull UUID mappingId, @NonNull DefaultGLMappingRequest request);

    /**
     * Deactivates a default GL mapping (soft delete).
     *
     * @param mappingId the ID of the mapping to deactivate
     * @throws IllegalArgumentException if mapping not found
     */
    void deactivateDefaultMapping(@NonNull UUID mappingId);

    /**
     * Retrieves a default GL mapping by ID.
     *
     * @param mappingId the mapping ID
     * @return the mapping response
     * @throws IllegalArgumentException if mapping not found
     */
    @NonNull
    DefaultGLMappingResponse getDefaultMapping(@NonNull UUID mappingId);

    /**
     * Lists all default mappings with pagination.
     *
     * @param page page index
     * @param size page size
     * @return paginated list of default mappings
     */
    @NonNull
    DefaultGLMappingListResponse listDefaultMappings(int page, int size);

    /**
     * Finds active default mapping for a specific event type and organization.
     *
     * @param eventType      the event type
     * @param organizationId the organization ID (null for global defaults)
     * @return the most specific active default mapping, if found
     */
    @Nullable
    DefaultGLMappingResponse findActiveDefaultForEvent(@NonNull String eventType, @Nullable UUID organizationId);

    /**
     * Lists all active default mappings for a specific event type.
     *
     * @param eventType the event type
     * @return list of active defaults
     */
    @NonNull
    List<DefaultGLMappingResponse> findByEventType(@NonNull String eventType);

    /**
     * Lists all active default mappings for a specific organization.
     *
     * @param organizationId the organization ID
     * @return list of active defaults
     */
    @NonNull
    List<DefaultGLMappingResponse> findByOrganization(@NonNull UUID organizationId);

    /**
     * Lists all global default mappings (organizationId IS NULL).
     *
     * @return list of global active defaults
     */
    @NonNull
    List<DefaultGLMappingResponse> findAllGlobalDefaults();
}
