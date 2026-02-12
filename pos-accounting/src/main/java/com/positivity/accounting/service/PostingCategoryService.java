package com.positivity.accounting.service;

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

import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryListResponse;
import com.positivity.accounting.internal.dto.PostingCategoryResponse;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;
import com.positivity.accounting.internal.entity.PostingCategory;
import com.positivity.accounting.internal.repository.GLMappingRepository;
import com.positivity.accounting.internal.repository.PostingCategoryRepository;

/**
 * Service for Posting Category management.
 * Handles CRUD operations and lifecycle management for posting categories.
 */
@Service
public class PostingCategoryService {

    private static final Logger log = LoggerFactory.getLogger(PostingCategoryService.class);

    private final PostingCategoryRepository postingCategoryRepository;
    private final GLMappingRepository glMappingRepository;

    public PostingCategoryService(
            @NonNull PostingCategoryRepository postingCategoryRepository,
            @NonNull GLMappingRepository glMappingRepository) {
        this.postingCategoryRepository = postingCategoryRepository;
        this.glMappingRepository = glMappingRepository;
    }

    /**
     * Creates a new posting category.
     * 
     * @param request the posting category creation request
     * @return the created posting category response
     * @throws ResponseStatusException with BAD_REQUEST if category name already
     *                                 exists
     */
    @Transactional
    public PostingCategoryResponse createPostingCategory(@NonNull PostingCategoryCreateRequest request) {
        String normalizedCategoryName = request.getCategoryName().trim();
        log.info("Creating posting category: {}", normalizedCategoryName);

        // Validate uniqueness
        if (postingCategoryRepository.existsByCategoryName(normalizedCategoryName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Posting category with name '" + normalizedCategoryName + "' already exists");
        }

        PostingCategory category = new PostingCategory();
        category.setCategoryName(normalizedCategoryName);
        category.setDescription(request.getDescription());
        category.setIsActive(true);
        category.setCreatedBy(request.getCreatedBy());
        category.setModifiedBy(request.getCreatedBy());

        PostingCategory saved = postingCategoryRepository.save(category);
        log.info("Created posting category: {} with ID: {}", saved.getCategoryName(), saved.getPostingCategoryId());

        return toResponse(saved);
    }

    /**
     * Retrieves a posting category by ID.
     * 
     * @param postingCategoryId the posting category identifier
     * @return the posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     */
    @Transactional(readOnly = true)
    public PostingCategoryResponse getPostingCategory(@NonNull UUID postingCategoryId) {
        log.info("Retrieving posting category: {}", postingCategoryId);

        PostingCategory category = postingCategoryRepository.findById(postingCategoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Posting category not found: " + postingCategoryId));

        return toResponse(category);
    }

    /**
     * Updates an existing posting category.
     * 
     * @param postingCategoryId the posting category identifier
     * @param request           the update request
     * @return the updated posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     * @throws ResponseStatusException with BAD_REQUEST if name conflicts
     */
    @Transactional
    public PostingCategoryResponse updatePostingCategory(
            @NonNull UUID postingCategoryId,
            @NonNull PostingCategoryUpdateRequest request) {
        log.info("Updating posting category: {}", postingCategoryId);

        PostingCategory category = postingCategoryRepository.findById(postingCategoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Posting category not found: " + postingCategoryId));

        // Validate uniqueness if name is changing
        String trimmedName = request.getCategoryName().trim();
        if (!category.getCategoryName().equals(trimmedName) &&
                postingCategoryRepository.existsByCategoryName(trimmedName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Posting category with name '" + trimmedName + "' already exists");
        }

        category.setCategoryName(trimmedName);
        category.setDescription(request.getDescription());
        category.setModifiedBy(request.getModifiedBy());

        PostingCategory updated = postingCategoryRepository.save(category);
        log.info("Updated posting category: {}", updated.getPostingCategoryId());

        return toResponse(updated);
    }

    /**
     * Lists posting categories with pagination and filtering.
     * 
     * @param page     page number (0-based)
     * @param size     page size
     * @param sort     sort field
     * @param isActive filter by active status (null for all)
     * @return paginated list of posting categories
     */
    @Transactional(readOnly = true)
    public PostingCategoryListResponse listPostingCategories(
            int page,
            int size,
            @NonNull String sort,
            Boolean isActive) {
        log.info("Listing posting categories: page={}, size={}, sort={}, isActive={}", page, size, sort, isActive);

        Pageable pageable = PageRequest.of(page, size, Sort.by(sort));
        Page<PostingCategory> categoryPage;

        if (isActive != null) {
            categoryPage = postingCategoryRepository.findAll(
                    (root, query, cb) -> cb.equal(root.get("isActive"), isActive),
                    pageable);
        } else {
            categoryPage = postingCategoryRepository.findAll(pageable);
        }

        PostingCategoryListResponse response = new PostingCategoryListResponse();
        response.setResults(categoryPage.getContent().stream()
                .map(this::toResponse)
                .toList());
        response.setTotalCount(categoryPage.getTotalElements());
        response.setPageNumber(page);
        response.setPageSize(size);
        response.setTotalPages(categoryPage.getTotalPages());

        return response;
    }

    /**
     * Deactivates a posting category.
     * Validates that no active GL mappings reference this category.
     * 
     * @param postingCategoryId the posting category identifier
     * @return the deactivated posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     * @throws ResponseStatusException with CONFLICT if active mappings exist
     */
    @Transactional
    public PostingCategoryResponse deactivatePostingCategory(@NonNull UUID postingCategoryId) {
        log.info("Deactivating posting category: {}", postingCategoryId);

        PostingCategory category = postingCategoryRepository.findById(postingCategoryId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Posting category not found: " + postingCategoryId));

        // Check for active mappings
        long activeMappingCount = glMappingRepository.countByPostingCategoryIdAndDeactivatedAtIsNull(postingCategoryId);
        if (activeMappingCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Posting category '" + category.getCategoryName() +
                            "' has " + activeMappingCount + " active GL mappings and cannot be deactivated");
        }

        category.setIsActive(false);
        PostingCategory deactivated = postingCategoryRepository.save(category);

        log.info("Deactivated posting category: {}", postingCategoryId);

        return toResponse(deactivated);
    }

    /**
     * Converts a PostingCategory entity to a response DTO.
     */
    private PostingCategoryResponse toResponse(@NonNull PostingCategory category) {
        return new PostingCategoryResponse(
                category.getPostingCategoryId(),
                category.getCategoryName(),
                category.getDescription(),
                category.getIsActive(),
                category.getCreatedAt(),
                category.getCreatedBy(),
                category.getModifiedAt(),
                category.getModifiedBy());
    }
}
