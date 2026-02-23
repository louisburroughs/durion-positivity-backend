package com.positivity.accounting.service;

import java.util.UUID;

import org.springframework.web.server.ResponseStatusException;

import com.positivity.accounting.internal.dto.PostingCategoryCreateRequest;
import com.positivity.accounting.internal.dto.PostingCategoryListResponse;
import com.positivity.accounting.internal.dto.PostingCategoryResponse;
import com.positivity.accounting.internal.dto.PostingCategoryUpdateRequest;

public interface PostingCategoryService {

    /**
     * Creates a new posting category.
     * 
     * @param request the posting category creation request
     * @return the created posting category response
     * @throws ResponseStatusException with BAD_REQUEST if category name already
     *                                 exists
     */
    PostingCategoryResponse createPostingCategory(PostingCategoryCreateRequest request);

    /**
     * Retrieves a posting category by ID.
     * 
     * @param postingCategoryId the posting category identifier
     * @return the posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     */
    PostingCategoryResponse getPostingCategory(UUID postingCategoryId);

    /**
     * Updates an existing posting category.
     * 
     * @param postingCategoryId the posting category identifier
     * @param request           the update request
     * @return the updated posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     * @throws ResponseStatusException with BAD_REQUEST if name conflicts
     */
    PostingCategoryResponse updatePostingCategory(
            UUID postingCategoryId,
            PostingCategoryUpdateRequest request);

    /**
     * Lists posting categories with pagination and filtering.
     * 
     * @param page     page number (0-based)
     * @param size     page size
     * @param sort     sort field
     * @param isActive filter by active status (null for all)
     * @return paginated list of posting categories
     */
    PostingCategoryListResponse listPostingCategories(
            int page,
            int size,
            String sort,
            Boolean isActive);

    /**
     * Deactivates a posting category.
     * Validates that no active GL mappings reference this category.
     * 
     * @param postingCategoryId the posting category identifier
     * @return the deactivated posting category response
     * @throws ResponseStatusException with NOT_FOUND if posting category not found
     * @throws ResponseStatusException with CONFLICT if active mappings exist
     */
    PostingCategoryResponse deactivatePostingCategory(UUID postingCategoryId);

}