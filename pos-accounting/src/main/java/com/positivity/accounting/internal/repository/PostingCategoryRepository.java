package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PostingCategory;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

/**
 * Repository for Posting Category entity.
 * Supports looking up categories by name.
 */
@Repository
public interface PostingCategoryRepository
        extends JpaRepository<PostingCategory, UUID>, JpaSpecificationExecutor<PostingCategory> {

    /**
     * Find a category by name.
     */
    Optional<PostingCategory> findByCategoryName(String categoryName);

    /**
     * Find all active categories.
     */
    List<PostingCategory> findByIsActive(Boolean isActive);

    /**
     * Find categories by name containing search term.
     */
    List<PostingCategory> findByCategoryNameContainingIgnoreCase(String searchTerm);

    /**
     * Check if a category name is already in use.
     */
    boolean existsByCategoryName(String categoryName);
}
