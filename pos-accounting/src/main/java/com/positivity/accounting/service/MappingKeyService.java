package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.*;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for Mapping Key management.
 * Handles CRUD operations and lifecycle management for mapping keys.
 */
@Service
public class MappingKeyService {

    private static final Logger log = LoggerFactory.getLogger(MappingKeyService.class);

    private final MappingKeyRepository mappingKeyRepository;
    private final PostingCategoryRepository postingCategoryRepository;
    private final GLMappingRepository glMappingRepository;

    public MappingKeyService(
            @NonNull MappingKeyRepository mappingKeyRepository,
            @NonNull PostingCategoryRepository postingCategoryRepository,
            @NonNull GLMappingRepository glMappingRepository) {
        this.mappingKeyRepository = mappingKeyRepository;
        this.postingCategoryRepository = postingCategoryRepository;
        this.glMappingRepository = glMappingRepository;
    }

    /**
     * Creates a new mapping key.
     * 
     * @param request the mapping key creation request
     * @return the created mapping key response
     * @throws IllegalArgumentException if posting category not found or key name
     *                                  already exists
     */
    @Transactional
    public MappingKeyResponse createMappingKey(@NonNull MappingKeyCreateRequest request) {
        log.info("Creating mapping key: {} for category: {}", request.getKeyName(), request.getPostingCategoryId());

        // Validate posting category exists
        PostingCategory category = postingCategoryRepository.findById(request.getPostingCategoryId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Posting category not found: " + request.getPostingCategoryId()));

        // Validate uniqueness within category
        String trimmedName = request.getKeyName().trim();
        if (mappingKeyRepository.existsByPostingCategoryIdAndKeyName(
                request.getPostingCategoryId(), trimmedName)) {
            throw new IllegalArgumentException(
                    "Mapping key with name '" + trimmedName +
                            "' already exists in posting category '" + category.getCategoryName() + "'");
        }

        MappingKey mappingKey = new MappingKey();
        mappingKey.setPostingCategoryId(request.getPostingCategoryId());
        mappingKey.setKeyName(trimmedName);
        mappingKey.setDescription(request.getDescription());
        mappingKey.setIsActive(true);
        mappingKey.setCreatedBy(request.getCreatedBy());
        mappingKey.setModifiedBy(request.getCreatedBy());

        MappingKey saved = mappingKeyRepository.save(mappingKey);
        log.info("Created mapping key: {} with ID: {}", saved.getKeyName(), saved.getMappingKeyId());

        return toResponse(saved, category.getCategoryName());
    }

    /**
     * Retrieves a mapping key by ID.
     * 
     * @param mappingKeyId the mapping key identifier
     * @return the mapping key response
     * @throws IllegalArgumentException if mapping key not found
     */
    @Transactional(readOnly = true)
    public MappingKeyResponse getMappingKey(@NonNull UUID mappingKeyId) {
        log.info("Retrieving mapping key: {}", mappingKeyId);

        MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping key not found: " + mappingKeyId));

        PostingCategory category = postingCategoryRepository.findById(mappingKey.getPostingCategoryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Posting category not found: " + mappingKey.getPostingCategoryId()));

        return toResponse(mappingKey, category.getCategoryName());
    }

    /**
     * Updates an existing mapping key.
     * 
     * @param mappingKeyId the mapping key identifier
     * @param request      the update request
     * @return the updated mapping key response
     * @throws IllegalArgumentException if mapping key not found or name conflicts
     */
    @Transactional
    public MappingKeyResponse updateMappingKey(
            @NonNull UUID mappingKeyId,
            @NonNull MappingKeyUpdateRequest request) {
        log.info("Updating mapping key: {}", mappingKeyId);

        MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping key not found: " + mappingKeyId));

        PostingCategory category = postingCategoryRepository.findById(mappingKey.getPostingCategoryId())
                .orElseThrow(() -> new IllegalStateException(
                        "Posting category not found: " + mappingKey.getPostingCategoryId()));

        // Validate uniqueness if name is changing
        String trimmedName = request.getKeyName().trim();
        if (!mappingKey.getKeyName().equals(trimmedName) &&
                mappingKeyRepository.existsByPostingCategoryIdAndKeyName(
                        mappingKey.getPostingCategoryId(), trimmedName)) {
            throw new IllegalArgumentException(
                    "Mapping key with name '" + trimmedName +
                            "' already exists in posting category '" + category.getCategoryName() + "'");
        }

        mappingKey.setKeyName(trimmedName);
        mappingKey.setDescription(request.getDescription());
        mappingKey.setModifiedBy(request.getModifiedBy());

        MappingKey updated = mappingKeyRepository.save(mappingKey);
        log.info("Updated mapping key: {}", updated.getMappingKeyId());

        return toResponse(updated, category.getCategoryName());
    }

    /**
     * Lists mapping keys for a posting category.
     * 
     * @param postingCategoryId the posting category identifier
     * @param page              page number (0-based)
     * @param size              page size
     * @param sort              sort field
     * @param isActive          filter by active status (null for all)
     * @return paginated list of mapping keys
     */
    @Transactional(readOnly = true)
    public MappingKeyListResponse listMappingKeysByCategory(
            @NonNull UUID postingCategoryId,
            int page,
            int size,
            @NonNull String sort,
            Boolean isActive) {
        log.info("Listing mapping keys for category: {}, page={}, size={}, sort={}, isActive={}",
                postingCategoryId, page, size, sort, isActive);

        // Validate category exists
        PostingCategory category = postingCategoryRepository.findById(postingCategoryId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Posting category not found: " + postingCategoryId));

        Pageable pageable = PageRequest.of(page, size, Sort.by(sort));
        Page<MappingKey> keyPage;

        if (isActive != null) {
            keyPage = mappingKeyRepository.findAll(
                    (root, query, cb) -> cb.and(
                            cb.equal(root.get("postingCategoryId"), postingCategoryId),
                            cb.equal(root.get("isActive"), isActive)),
                    pageable);
        } else {
            keyPage = mappingKeyRepository.findAll(
                    (root, query, cb) -> cb.equal(root.get("postingCategoryId"), postingCategoryId),
                    pageable);
        }

        MappingKeyListResponse response = new MappingKeyListResponse();
        response.setResults(keyPage.getContent().stream()
                .map(mk -> toResponse(mk, category.getCategoryName()))
                .collect(Collectors.toList()));
        response.setTotalCount(keyPage.getTotalElements());
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalPages(keyPage.getTotalPages());

        return response;
    }

    /**
     * Deactivates a mapping key.
     * Validates that no active GL mappings reference this key.
     * 
     * @param mappingKeyId the mapping key identifier
     * @throws IllegalArgumentException if mapping key not found
     * @throws IllegalStateException    if active mappings exist
     */
    @Transactional
    public void deactivateMappingKey(@NonNull UUID mappingKeyId) {
        log.info("Deactivating mapping key: {}", mappingKeyId);

        MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                .orElseThrow(() -> new IllegalArgumentException("Mapping key not found: " + mappingKeyId));

        // Check for active mappings
        long activeMappingCount = glMappingRepository.countByMappingKeyIdAndDeactivatedAtIsNull(mappingKeyId);
        if (activeMappingCount > 0) {
            throw new IllegalStateException(
                    "Mapping key '" + mappingKey.getKeyName() +
                            "' has " + activeMappingCount + " active GL mappings and cannot be deactivated");
        }

        mappingKey.setIsActive(false);
        mappingKeyRepository.save(mappingKey);

        log.info("Deactivated mapping key: {}", mappingKeyId);
    }

    /**
     * Converts a MappingKey entity to a response DTO.
     */
    private MappingKeyResponse toResponse(@NonNull MappingKey mappingKey, @NonNull String categoryName) {
        return new MappingKeyResponse(
                mappingKey.getMappingKeyId(),
                mappingKey.getPostingCategoryId(),
                categoryName,
                mappingKey.getKeyName(),
                mappingKey.getDescription(),
                mappingKey.getIsActive(),
                mappingKey.getCreatedAt(),
                mappingKey.getCreatedBy(),
                mappingKey.getModifiedAt(),
                mappingKey.getModifiedBy());
    }
}
