package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for Posting Rule Version entity.
 * Supports querying rule versions by rule set, state, and version number.
 */
@Repository
public interface PostingRuleVersionRepository extends JpaRepository<PostingRuleVersion, UUID> {

    /**
     * Find all versions for a given posting rule set.
     */
    List<PostingRuleVersion> findByPostingRuleSetId(UUID postingRuleSetId);

    /**
     * Find all versions for a rule set with a specific state.
     */
    List<PostingRuleVersion> findByPostingRuleSetIdAndState(UUID postingRuleSetId, PostingRuleSetState state);

    /**
     * Find the latest version number for a rule set.
     */
    Optional<PostingRuleVersion> findTopByPostingRuleSetIdOrderByVersionNumberDesc(UUID postingRuleSetId);

    /**
     * Find a specific version by rule set and version number.
     */
    Optional<PostingRuleVersion> findByPostingRuleSetIdAndVersionNumber(UUID postingRuleSetId, Integer versionNumber);
}
