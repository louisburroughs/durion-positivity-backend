package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for Posting Rule Version entity.
 * Supports querying rule versions by rule set, state, and version number.
 */
@Repository
public interface PostingRuleVersionRepository extends JpaRepository<PostingRuleVersion, String> {

    /**
     * Find all versions for a given posting rule set.
     */
    List<PostingRuleVersion> findByPostingRuleSetId(String postingRuleSetId);

    /**
     * Find all versions for a rule set with a specific state.
     */
    List<PostingRuleVersion> findByPostingRuleSetIdAndState(String postingRuleSetId, PostingRuleSetState state);

    /**
     * Find the latest version number for a rule set.
     */
    Optional<PostingRuleVersion> findTopByPostingRuleSetIdOrderByVersionNumberDesc(String postingRuleSetId);

    /**
     * Find a specific version by rule set and version number.
     */
    Optional<PostingRuleVersion> findByPostingRuleSetIdAndVersionNumber(String postingRuleSetId, Integer versionNumber);
}
