package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetListResponse;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.dto.PostingRuleVersionResponse;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;

import java.util.List;
import java.util.UUID;

/**
 * Interface for Posting Rule Set management.
 * Handles versioned rule sets that drive event-to-journal-entry conversion.
 * Rule sets are immutable once PUBLISHED; changes require new versions.
 */
public interface PostingRuleService {

    // ── DTO-based methods (used by controllers) ──────────────────────

    /**
     * Creates a new posting rule set with an initial DRAFT version.
     * Returns the created rule set as a response DTO.
     */
    PostingRuleSetResponse createPostingRuleSetWithVersion(PostingRuleSetCreateRequest request);

    /**
     * Publishes the latest DRAFT version of a posting rule set.
     * Returns the published version as a response DTO.
     */
    PostingRuleVersionResponse publishRuleSet(UUID ruleSetId);

    /**
     * Archives the PUBLISHED version of a posting rule set.
     * Returns the archived version as a response DTO.
     */
    PostingRuleVersionResponse archiveRuleSet(UUID ruleSetId);

    /**
     * Lists posting rule sets as a paginated response DTO.
     */
    PostingRuleSetListResponse listRuleSetsAsResponse(int page, int size, String sort);

    /**
     * Retrieves a posting rule set as a response DTO.
     */
    PostingRuleSetResponse getPostingRuleSetAsResponse(UUID ruleSetId);

    /**
     * Lists versions of a posting rule set as response DTOs.
     */
    List<PostingRuleVersionResponse> listVersionsAsResponse(UUID ruleSetId, int page, int size);

    /**
     * Updates a posting rule set from a DTO request (metadata only).
     * Converts request to entity and delegates to updatePostingRuleSet.
     */
    PostingRuleSet updatePostingRuleSetFromRequest(UUID ruleSetId, PostingRuleSetCreateRequest request);

    // ── Entity-based methods (used by internal services) ─────────────

    /**
     * Creates a new posting rule set in DRAFT status.
     */
    PostingRuleSet createPostingRuleSet(PostingRuleSet ruleSet);

    /**
     * Retrieves a posting rule set by ID.
     */
    PostingRuleSet getPostingRuleSet(UUID ruleSetId);

    /**
     * Updates a posting rule set (metadata only).
     */
    PostingRuleSet updatePostingRuleSet(UUID ruleSetId, PostingRuleSet updates);

    /**
     * Creates a new version of a posting rule set.
     */
    PostingRuleVersion createVersion(UUID ruleSetId, PostingRuleVersion version);

    /**
     * Updates a draft version (draft versions only).
     */
    PostingRuleVersion updateVersion(UUID versionId, PostingRuleVersion updates);

    /**
     * Publishes a version (DRAFT → PUBLISHED).
     * Automatically archives any previously PUBLISHED version.
     */
    PostingRuleVersion publishVersion(UUID versionId);

    /**
     * Archives a version (PUBLISHED → ARCHIVED).
     */
    PostingRuleVersion archiveVersion(UUID versionId);

    /**
     * Lists all rule sets.
     */
    List<PostingRuleSet> listRuleSets();

    /**
     * Lists all versions of a rule set.
     */
    List<PostingRuleVersion> listVersions(UUID ruleSetId);
}
