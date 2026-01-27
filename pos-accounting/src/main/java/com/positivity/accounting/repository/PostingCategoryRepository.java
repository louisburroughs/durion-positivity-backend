package com.positivity.accounting.repository;

import com.positivity.accounting.entity.PostingCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Posting Category entity.
 * Supports looking up categories by name.
 */
@Repository
public interface PostingCategoryRepository extends JpaRepository<PostingCategory, String> {

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
