package com.positivity.accounting.service;

import java.util.UUID;

import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyListResponse;
import com.positivity.accounting.internal.dto.MappingKeyResponse;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;

public interface MappingKeyService {

    /**
     * Creates a new mapping key.
     * 
     * @param request the mapping key creation request
     * @return the created mapping key response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     * @throws ResponseStatusException with BAD_REQUEST if key name already exists
     */
    MappingKeyResponse createMappingKey(MappingKeyCreateRequest request);

    /**
     * Retrieves a mapping key by ID.
     * 
     * @param mappingKeyId the mapping key identifier
     * @return the mapping key response
     * @throws ResponseStatusException with NOT_FOUND if mapping key or category not
     *                                 found
     */
    MappingKeyResponse getMappingKey(UUID mappingKeyId);

    /**
     * Updates an existing mapping key.
     * 
     * @param mappingKeyId the mapping key identifier
     * @param request      the update request
     * @return the updated mapping key response
     * @throws ResponseStatusException with NOT_FOUND if mapping key or category not
     *                                 found
     * @throws ResponseStatusException with BAD_REQUEST if name conflicts
     */
    MappingKeyResponse updateMappingKey(
            UUID mappingKeyId,
            MappingKeyUpdateRequest request);

    /**
     * Lists mapping keys for a posting category.
     * 
     * @param postingCategoryId the posting category identifier
     * @param page              page number (0-based)
     * @param size              page size
     * @param sort              sort field
     * @param isActive          filter by active status (null for all)
     * @return paginated list of mapping keys
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     */
    MappingKeyListResponse listMappingKeysByCategory(
            UUID postingCategoryId,
            int page,
            int size,
            String sort,
            Boolean isActive);

    /**
     * Deactivates a mapping key.
     * Validates that no active GL mappings reference this key.
     * 
     * @param mappingKeyId the mapping key identifier
     * @return the deactivated mapping key response
     * @throws ResponseStatusException with NOT_FOUND if mapping key not found
     * @throws ResponseStatusException with CONFLICT if active mappings exist
     */
    MappingKeyResponse deactivateMappingKey(UUID mappingKeyId);

}