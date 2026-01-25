package com.positivity.accounting.repository;

import com.positivity.accounting.entity.PostingRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for Posting Rule Set entity.
 * Supports querying active rule sets, versions, and temporal filters.
 */
@Repository
public interface PostingRuleSetRepository extends JpaRepository<PostingRuleSet, String> {

    /**
     * Find all rule sets for an organization.
     */
    List<PostingRuleSet> findByOrganizationId(String organizationId);

    /**
     * Find all PUBLISHED rule sets effective at a given time for posting.
     */
    @Query("SELECT prs FROM PostingRuleSet prs " +
            "WHERE prs.organizationId = :organizationId " +
            "AND prs.status = 'PUBLISHED' " +
            "AND prs.effectiveStartDate <= :transactionDate " +
            "AND (prs.effectiveEndDate IS NULL OR prs.effectiveEndDate > :transactionDate) " +
            "ORDER BY prs.versionNumber DESC")
    List<PostingRuleSet> findActiveRuleSetsAt(String organizationId, LocalDateTime transactionDate);

    /**
     * Find a specific rule set by name and version.
     */
    Optional<PostingRuleSet> findByNameAndVersionNumber(String name, Integer versionNumber);

    /**
     * Find all versions of a rule set by name.
     */
    @Query("SELECT prs FROM PostingRuleSet prs WHERE prs.name = :name ORDER BY prs.versionNumber DESC")
    List<PostingRuleSet> findVersionsByName(String name);

    /**
     * Get the latest PUBLISHED version of a rule set.
     */
    @Query("SELECT prs FROM PostingRuleSet prs " +
            "WHERE prs.name = :name AND prs.status = 'PUBLISHED' " +
            "ORDER BY prs.versionNumber DESC LIMIT 1")
    Optional<PostingRuleSet> findLatestPublishedVersion(String name);
}
