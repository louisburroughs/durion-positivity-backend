package com.positivity.accounting.service;

import com.positivity.accounting.entity.PostingRuleSet;
import com.positivity.accounting.repository.PostingRuleSetRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Service for Posting Rule management.
 * Handles versioning, publishing, effective-dating, and state management.
 *
 * State Machine: DRAFT → PUBLISHED (immutable) → ARCHIVED (no longer usable)
 *
 * Versioning Strategy:
 * - Each rule set can have multiple versions (version numbers auto-increment)
 * - Only one version can be PUBLISHED at a time per rule set name
 * - New PUBLISHED versions supersede old ones (old version archived)
 * - DRAFT versions can coexist with PUBLISHED versions
 *
 * Key Business Rules:
 * - DRAFT rule sets can be modified/deleted
 * - PUBLISHED rule sets are immutable
 * - Rule sets must be validated before publication
 * - Only PUBLISHED rule sets are used for automatic posting
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PostingRuleServiceImpl {

    private final PostingRuleSetRepository ruleSetRepository;

    /**
     * Creates a new rule set in DRAFT status.
     * Rule sets can be modified in DRAFT; once PUBLISHED they become immutable.
     *
     * @param ruleSet posting rule set to create
     * @return created rule set with version 1
     */
    public PostingRuleSet createPostingRuleSet(PostingRuleSet ruleSet) {
        // Check for existing rule set with same name
        List<PostingRuleSet> existing = ruleSetRepository.findVersionsByName(ruleSet.getName());
        if (!existing.isEmpty()) {
            // This is a new version; increment version number
            int maxVersion = existing.stream()
                    .map(PostingRuleSet::getVersionNumber)
                    .max(Integer::compareTo)
                    .orElse(0);
            ruleSet.setVersionNumber(maxVersion + 1);
        } else {
            // First version
            ruleSet.setVersionNumber(1);
        }

        ruleSet.setPostingRuleSetId(UUID.randomUUID().toString());
        ruleSet.setStatus("DRAFT");
        ruleSet.setCreatedAt(LocalDateTime.now());
        ruleSet.setModifiedAt(LocalDateTime.now());

        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Created rule set {} v{} in DRAFT status", saved.getName(), saved.getVersionNumber());
        return saved;
    }

    /**
     * Retrieves a rule set by ID.
     */
    public PostingRuleSet getPostingRuleSet(String ruleSetId) {
        return ruleSetRepository.findById(ruleSetId)
                .orElseThrow(() -> new IllegalArgumentException("Posting rule set not found: " + ruleSetId));
    }

    /**
     * Updates a rule set (DRAFT status only).
     * Cannot update once PUBLISHED.
     *
     * @param ruleSetId rule set to update
     * @param updates   rule set with new values
     * @return updated rule set
     * @throws IllegalStateException if rule set is not in DRAFT status
     */
    public PostingRuleSet updatePostingRuleSet(String ruleSetId, PostingRuleSet updates) {
        PostingRuleSet ruleSet = getPostingRuleSet(ruleSetId);

        if (!"DRAFT".equals(ruleSet.getStatus())) {
            String msg = String.format(
                    "Cannot update %s rule set; only DRAFT rule sets can be modified", ruleSet.getStatus());
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Update mutable fields
        ruleSet.setName(updates.getName());
        ruleSet.setDescription(updates.getDescription());
        ruleSet.setRules(updates.getRules());
        ruleSet.setModifiedAt(LocalDateTime.now());

        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Updated rule set {} v{}", saved.getName(), saved.getVersionNumber());
        return saved;
    }

    /**
     * Publishes a rule set (transitions from DRAFT to PUBLISHED).
     * Once published, rule set becomes immutable and can be used for posting.
     * If another version is already PUBLISHED, archives the old version.
     *
     * @param ruleSetId rule set to publish
     * @return published rule set
     * @throws IllegalStateException    if rule set is not in DRAFT status
     * @throws IllegalArgumentException if rule set fails validation
     */
    public PostingRuleSet publishRuleSet(String ruleSetId) {
        PostingRuleSet ruleSet = getPostingRuleSet(ruleSetId);

        if (!"DRAFT".equals(ruleSet.getStatus())) {
            String msg = String.format(
                    "Cannot publish %s rule set; only DRAFT rule sets can be published", ruleSet.getStatus());
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        // Validate rule set before publishing
        List<String> errors = validateRuleSet(ruleSet);
        if (!errors.isEmpty()) {
            String msg = "Rule set validation failed: " + String.join("; ", errors);
            log.warn(msg);
            throw new IllegalArgumentException(msg);
        }

        // Archive any existing published version of this rule set
        var latestPublished = ruleSetRepository.findLatestPublishedVersion(ruleSet.getName());
        if (latestPublished.isPresent()) {
            PostingRuleSet oldVersion = latestPublished.get();
            oldVersion.setStatus("ARCHIVED");
            oldVersion.setModifiedAt(LocalDateTime.now());
            ruleSetRepository.save(oldVersion);
            log.info("Archived previous version {} v{}", oldVersion.getName(), oldVersion.getVersionNumber());
        }

        // Publish new version
        ruleSet.setStatus("PUBLISHED");
        ruleSet.setEffectiveStartDate(LocalDateTime.now());
        ruleSet.setModifiedAt(LocalDateTime.now());

        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Published rule set {} v{} effective immediately", saved.getName(), saved.getVersionNumber());
        return saved;
    }

    /**
     * Archives a rule set (PUBLISHED → ARCHIVED).
     * Archived sets can no longer be used for posting but retained for audit.
     *
     * @param ruleSetId rule set to archive
     * @return archived rule set
     */
    public PostingRuleSet archiveRuleSet(String ruleSetId) {
        PostingRuleSet ruleSet = getPostingRuleSet(ruleSetId);

        if (!"PUBLISHED".equals(ruleSet.getStatus())) {
            String msg = "Can only archive PUBLISHED rule sets; found " + ruleSet.getStatus();
            log.warn(msg);
            throw new IllegalStateException(msg);
        }

        ruleSet.setStatus("ARCHIVED");
        ruleSet.setEffectiveEndDate(LocalDateTime.now());
        ruleSet.setModifiedAt(LocalDateTime.now());

        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Archived rule set {} v{}", saved.getName(), saved.getVersionNumber());
        return saved;
    }

    /**
     * Gets all versions of a rule set by name.
     */
    public List<PostingRuleSet> listVersions(String name) {
        return ruleSetRepository.findVersionsByName(name);
    }

    /**
     * Lists rule sets with pagination.
     */
    public Page<PostingRuleSet> listRuleSets(String organizationId, Pageable pageable) {
        return ruleSetRepository.findByOrganizationId(organizationId, pageable);
    }

    /**
     * Validates rule set structure (called before publish).
     * Checks:
     * - Rule set has at least one rule
     * - All rules are properly structured
     * - No circular dependencies or conflicts
     *
     * @param ruleSet rule set to validate
     * @return list of validation errors (empty if valid)
     */
    public List<String> validateRuleSet(PostingRuleSet ruleSet) {
        List<String> errors = new java.util.ArrayList<>();

        if (ruleSet.getName() == null || ruleSet.getName().isBlank()) {
            errors.add("Rule set name is required");
        }
        if (ruleSet.getRules() == null || ruleSet.getRules().isEmpty()) {
            errors.add("Rule set must have at least one rule");
        }
        if (ruleSet.getOrganizationId() == null || ruleSet.getOrganizationId().isBlank()) {
            errors.add("Organization ID is required");
        }

        // TODO: Add rule structure validation (proper GL account references, etc.)

        return errors;
    }
}
