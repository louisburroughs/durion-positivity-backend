package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PostingRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Posting Rule Set entity.
 * Supports querying rule sets by name and event type.
 * Note: For version-specific queries (status, effective dates), use
 * PostingRuleVersionRepository.
 */
@Repository
public interface PostingRuleSetRepository extends JpaRepository<PostingRuleSet, UUID> {

        /**
         * Find all rule sets by event type.
         */
        List<PostingRuleSet> findByEventType(String eventType);

        /**
         * Find a specific rule set by name.
         */
        Optional<PostingRuleSet> findByName(String name);

        /**
         * Find all rule sets with name containing the search term.
         */
        List<PostingRuleSet> findByNameContainingIgnoreCase(String searchTerm);

        /**
         * Find a posting rule set by ID with versions eagerly fetched.
         * Use this method when versions need to be accessed outside a transaction.
         */
        @Query("SELECT prs FROM PostingRuleSet prs LEFT JOIN FETCH prs.versions WHERE prs.postingRuleSetId = :id")
        Optional<PostingRuleSet> findByIdWithVersions(@Param("id") UUID id);
}
