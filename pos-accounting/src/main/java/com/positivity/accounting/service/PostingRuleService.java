package com.positivity.accounting.service;

import com.positivity.accounting.entity.PostingRuleSet;
import com.positivity.accounting.entity.PostingRuleVersion;

import java.util.List;

/**
 * Interface for Posting Rule Set management.
 * Handles versioned rule sets that drive event-to-journal-entry conversion.
 * Rule sets are immutable once PUBLISHED; changes require new versions.
 */
public interface PostingRuleService {

    /**
     * Creates a new posting rule set in DRAFT status.
     */
    PostingRuleSet createPostingRuleSet(PostingRuleSet ruleSet);

    /**
     * Retrieves a posting rule set by ID.
     */
    PostingRuleSet getPostingRuleSet(String ruleSetId);

    /**
     * Updates a posting rule set (metadata only).
     */
    PostingRuleSet updatePostingRuleSet(String ruleSetId, PostingRuleSet updates);

    /**
     * Creates a new version of a posting rule set.
     */
    PostingRuleVersion createVersion(String ruleSetId, PostingRuleVersion version);

    /**
     * Updates a draft version (draft versions only).
     */
    PostingRuleVersion updateVersion(String versionId, PostingRuleVersion updates);

    /**
     * Publishes a version (DRAFT → PUBLISHED).
     * Automatically archives any previously PUBLISHED version.
     */
    PostingRuleVersion publishVersion(String versionId);

    /**
     * Archives a version (PUBLISHED → ARCHIVED).
     */
    PostingRuleVersion archiveVersion(String versionId);

    /**
     * Lists all rule sets for an organization.
     */
    List<PostingRuleSet> listRuleSets(String organizationId);

    /**
     * Lists all versions of a rule set.
     */
    List<PostingRuleVersion> listVersions(String ruleSetId);
}
