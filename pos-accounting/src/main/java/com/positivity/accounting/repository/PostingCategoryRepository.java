package com.positivity.accounting.repository;

import com.positivity.accounting.entity.PostingCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Posting Category entity.
 * Supports looking up categories by code and organization.
 */
@Repository
public interface PostingCategoryRepository extends JpaRepository<PostingCategory, String> {

    /**
     * Find a category by code within an organization.
     */
    Optional<PostingCategory> findByOrganizationIdAndCategoryCode(String organizationId, String categoryCode);

    /**
     * Find all categories for an organization.
     */
    List<PostingCategory> findByOrganizationId(String organizationId);

    /**
     * Check if a category code is already in use.
     */
    boolean existsByOrganizationIdAndCategoryCode(String organizationId, String categoryCode);
}
