package com.positivity.accounting.internal.service;

import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.dto.MappingKeyCreateRequest;
import com.positivity.accounting.internal.dto.MappingKeyListResponse;
import com.positivity.accounting.internal.dto.MappingKeyResponse;
import com.positivity.accounting.internal.dto.MappingKeyUpdateRequest;
import com.positivity.accounting.internal.entity.MappingKey;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.MappingKeyRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;
import com.positivity.accounting.service.MappingKeyService;

/**
 * Service for Mapping Key management.
 * Handles CRUD operations and lifecycle management for mapping keys.
 */
@Service
public class MappingKeyServiceImpl implements MappingKeyService {

        private static final String MAPPING_KEY_NOT_FOUND = "Mapping key not found: ";

        private static final String POSTING_CATEGORY_NOT_FOUND = "Posting category not found: ";

        private static final Logger log = LoggerFactory.getLogger(MappingKeyServiceImpl.class);

        private final MappingKeyRepository mappingKeyRepository;
        private final PostingCategoryRepository postingCategoryRepository;
        private final GLMappingRepository glMappingRepository;

        public MappingKeyServiceImpl(
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
         * @throws ResponseStatusException with NOT_FOUND if posting category not found
         * @throws ResponseStatusException with BAD_REQUEST if key name already exists
         */
        @Override
        @Transactional
        public MappingKeyResponse createMappingKey(@NonNull MappingKeyCreateRequest request) {

                if (log.isInfoEnabled()) {
                        log.info("Creating mapping key: {} for category: {}", maskText(request.getKeyName()),
                                        maskUUID(request.getPostingCategoryId()));
                }

                // Validate posting category exists
                PostingCategory category = postingCategoryRepository.findById(request.getPostingCategoryId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                POSTING_CATEGORY_NOT_FOUND + request.getPostingCategoryId()));

                // Validate uniqueness within category
                String trimmedName = request.getKeyName().trim();
                if (mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(
                                request.getPostingCategoryId(), trimmedName)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Mapping key with name '" + trimmedName +
                                                        "' already exists in posting category '"
                                                        + category.getCategoryName() + "'");
                }

                MappingKey mappingKey = new MappingKey();
                mappingKey.setPostingCategory(category);
                mappingKey.setKeyName(trimmedName);
                mappingKey.setDescription(request.getDescription());
                mappingKey.setIsActive(true);
                mappingKey.setCreatedBy(request.getCreatedBy());
                mappingKey.setModifiedBy(request.getCreatedBy());

                MappingKey saved = mappingKeyRepository.save(mappingKey);
                if (log.isInfoEnabled()) {
                        log.info("Created mapping key: {} with ID: {}", maskText(saved.getKeyName()),
                                        maskUUID(saved.getMappingKeyId()));
                }

                return toResponse(saved, category.getCategoryName());
        }

        /**
         * Retrieves a mapping key by ID.
         *
         * @param mappingKeyId the mapping key identifier
         * @return the mapping key response
         * @throws ResponseStatusException with NOT_FOUND if mapping key or category not
         *                                 found
         */
        @Override
        @Transactional(readOnly = true)
        public MappingKeyResponse getMappingKey(@NonNull UUID mappingKeyId) {
                if (log.isInfoEnabled()) {
                        log.info("Retrieving mapping key(mask): {}", maskUUID(mappingKeyId));
                }

                MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                MAPPING_KEY_NOT_FOUND + mappingKeyId));

                PostingCategory category = postingCategoryRepository.findById(mappingKey.getPostingCategoryId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                POSTING_CATEGORY_NOT_FOUND + mappingKey.getPostingCategoryId()));

                return toResponse(mappingKey, category.getCategoryName());
        }

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
        @Override
        @Transactional
        public MappingKeyResponse updateMappingKey(
                        @NonNull UUID mappingKeyId,
                        @NonNull MappingKeyUpdateRequest request) {
                if (log.isInfoEnabled()) {
                        log.info("Updating mapping key(mask): {}", maskUUID(mappingKeyId));
                }

                MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                MAPPING_KEY_NOT_FOUND + mappingKeyId));

                PostingCategory category = postingCategoryRepository.findById(mappingKey.getPostingCategoryId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                POSTING_CATEGORY_NOT_FOUND + mappingKey.getPostingCategoryId()));

                // Validate uniqueness if name is changing
                String trimmedName = request.getKeyName().trim();
                if (!mappingKey.getKeyName().equals(trimmedName) &&
                                mappingKeyRepository.existsByPostingCategory_PostingCategoryIdAndKeyName(
                                                mappingKey.getPostingCategoryId(), trimmedName)) {
                        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                        "Mapping key with name '" + trimmedName +
                                                        "' already exists in posting category '"
                                                        + category.getCategoryName() + "'");
                }

                mappingKey.setKeyName(trimmedName);
                mappingKey.setDescription(request.getDescription());
                mappingKey.setModifiedBy(request.getModifiedBy());

                MappingKey updated = mappingKeyRepository.save(mappingKey);
                if (log.isInfoEnabled()) {
                        log.info("Updated mapping key(mask): {}", maskUUID(updated.getMappingKeyId()));
                }

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
         * @throws ResponseStatusException with NOT_FOUND if posting category not found
         */
        @Override
        @Transactional(readOnly = true)
        public MappingKeyListResponse listMappingKeysByCategory(
                        @NonNull UUID postingCategoryId,
                        int page,
                        int size,
                        @NonNull String sort,
                        Boolean isActive) {

                if (log.isInfoEnabled()) {
                        log.info("Listing mapping keys: page={}, size={}, isActive={}", page, size, isActive);
                }

                // Validate category exists
                PostingCategory category = postingCategoryRepository.findById(postingCategoryId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                POSTING_CATEGORY_NOT_FOUND + postingCategoryId));

                Pageable pageable = PageRequest.of(page, size, Sort.by(sort));
                Page<MappingKey> keyPage;

                if (isActive != null) {
                        keyPage = mappingKeyRepository.findAll(
                                        (root, query, cb) -> cb.and(
                                                        cb.equal(root.get("postingCategory").get("postingCategoryId"),
                                                                        postingCategoryId),
                                                        cb.equal(root.get("isActive"), isActive)),
                                        pageable);
                } else {
                        keyPage = mappingKeyRepository.findAll(
                                        (root, query, cb) -> cb.equal(
                                                        root.get("postingCategory").get("postingCategoryId"),
                                                        postingCategoryId),
                                        pageable);
                }

                MappingKeyListResponse response = new MappingKeyListResponse();
                response.setResults(keyPage.getContent().stream()
                                .map(mk -> toResponse(mk, category.getCategoryName()))
                                .toList());
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
         * @return the deactivated mapping key response
         * @throws ResponseStatusException with NOT_FOUND if mapping key not found
         * @throws ResponseStatusException with CONFLICT if active mappings exist
         */
        @Override
        @Transactional
        public MappingKeyResponse deactivateMappingKey(@NonNull UUID mappingKeyId) {
                if (log.isInfoEnabled()) {
                        log.info("Deactivating mapping key: {}", maskUUID(mappingKeyId));
                }

                MappingKey mappingKey = mappingKeyRepository.findById(mappingKeyId)
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                MAPPING_KEY_NOT_FOUND + mappingKeyId));

                // Fetch category for response
                PostingCategory category = postingCategoryRepository.findById(mappingKey.getPostingCategoryId())
                                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                                                POSTING_CATEGORY_NOT_FOUND + mappingKey.getPostingCategoryId()));

                // Check for active mappings
                long activeMappingCount = glMappingRepository.countByMappingKeyIdAndDeactivatedAtIsNull(mappingKeyId);
                if (activeMappingCount > 0) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                        "Mapping key '" + mappingKey.getKeyName() +
                                                        "' has " + activeMappingCount
                                                        + " active GL mappings and cannot be deactivated");
                }

                mappingKey.setIsActive(false);
                MappingKey deactivated = mappingKeyRepository.save(mappingKey);

                if (log.isInfoEnabled()) {
                        log.info("Deactivated mapping key(mask): {}", maskUUID(mappingKeyId));
                }

                return toResponse(deactivated, category.getCategoryName());
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
                                mappingKey.getUpdatedAt(),
                                mappingKey.getModifiedBy());
        }

        /**
         * Masks a UUID for safe logging purposes.
         * Returns a format like "uuid-****-****-****-abcd" showing first segment and
         * last 4 chars.
         * This preserves enough information for correlation while preventing
         * information disclosure.
         *
         * @param uuid the UUID to mask
         * @return masked UUID string, or "null" if input is null
         */
        private String maskUUID(UUID uuid) {
                if (uuid == null) {
                        return "null";
                }
                String uuidString = uuid.toString();
                // Format: show first 8 chars (first segment) + last 4 chars for correlation
                // Example: "8f47e0c1-****-****-****-a1b2" from
                // "8f47e0c1-1234-5678-9abc-a1b2c3d4e5f6"
                return uuidString.substring(0, 8) + "-****-****-****-" + uuidString.substring(32);
        }

        /**
         * Masks free-text values for safe logging.
         * Preserves only short prefix/suffix for correlation.
         *
         * @param value text to mask
         * @return masked text, or "null" if input is null
         */
        private String maskText(String value) {
                if (value == null) {
                        return "null";
                }
                String trimmed = value.trim();
                if (trimmed.isEmpty()) {
                        return "***";
                }
                if (trimmed.length() <= 4) {
                        return "****";
                }
                return trimmed.substring(0, 2) + "****" + trimmed.substring(trimmed.length() - 2);
        }
}
