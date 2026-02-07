package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Service for Posting Rule Set and Version management.
 * Handles versioning, publishing, and state management for posting rules.
 *
 * Architecture:
 * - PostingRuleSet: Parent entity, contains metadata about a rule set
 * - PostingRuleVersion: Versioned entity, contains actual rules (DRAFT →
 * PUBLISHED → ARCHIVED)
 * - Only ONE PUBLISHED version per rule set can be active for posting
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class PostingRuleServiceImpl implements PostingRuleService {

    private final PostingRuleSetRepository ruleSetRepository;
    private final PostingRuleVersionRepository versionRepository;

    @Override
    public PostingRuleSet createPostingRuleSet(PostingRuleSet ruleSet) {
        ruleSet.setPostingRuleSetId(UUID.randomUUID());
        ruleSet.setCreatedAt(Instant.now());
        ruleSet.setModifiedAt(Instant.now());
        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Created rule set: {}", saved.getName());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public PostingRuleSet getPostingRuleSet(UUID ruleSetId) {
        return ruleSetRepository.findById(ruleSetId)
                .orElseThrow(() -> new IllegalArgumentException("Posting rule set not found: " + ruleSetId));
    }

    @Override
    public PostingRuleSet updatePostingRuleSet(UUID ruleSetId, PostingRuleSet updates) {
        PostingRuleSet ruleSet = getPostingRuleSet(ruleSetId);
        ruleSet.setName(updates.getName());
        ruleSet.setEventType(updates.getEventType());
        ruleSet.setDescription(updates.getDescription());
        ruleSet.setModifiedAt(Instant.now());
        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Updated rule set: {}", saved.getName());
        return saved;
    }

    @Override
    public PostingRuleVersion createVersion(UUID ruleSetId, PostingRuleVersion version) {
        PostingRuleSet ruleSet = getPostingRuleSet(ruleSetId);
        List<PostingRuleVersion> existing = versionRepository.findByPostingRuleSetId(ruleSetId);
        int maxVersion = existing.stream()
                .map(PostingRuleVersion::getVersionNumber)
                .max(Integer::compareTo)
                .orElse(0);

        version.setVersionId(UUID.randomUUID());
        version.setPostingRuleSetId(ruleSetId);
        version.setVersionNumber(maxVersion + 1);
        version.setState(PostingRuleSetState.DRAFT);
        version.setCreatedAt(Instant.now());
        version.setModifiedAt(Instant.now());

        PostingRuleVersion saved = versionRepository.save(version);
        log.info("Created version {} for rule set: {} (version count: {})",
                saved.getVersionNumber(), ruleSet.getName(), maxVersion + 1);
        return saved;
    }

    @Override
    public PostingRuleVersion updateVersion(UUID versionId, PostingRuleVersion updates) {
        PostingRuleVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        if (version.getState() != PostingRuleSetState.DRAFT) {
            throw new IllegalStateException("Can only update DRAFT versions");
        }

        version.setRulesDefinition(updates.getRulesDefinition());
        version.setModifiedAt(Instant.now());
        PostingRuleVersion saved = versionRepository.save(version);
        log.info("Updated version: {}", saved.getVersionId());
        return saved;
    }

    @Override
    public PostingRuleVersion publishVersion(UUID versionId) {
        PostingRuleVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        if (version.getState() != PostingRuleSetState.DRAFT) {
            throw new IllegalStateException("Can only publish DRAFT versions");
        }

        if (version.getRulesDefinition() == null || version.getRulesDefinition().isBlank()) {
            throw new IllegalArgumentException("Cannot publish: rules definition is empty");
        }

        // Archive any existing PUBLISHED version
        List<PostingRuleVersion> published = versionRepository.findByPostingRuleSetIdAndState(
                version.getPostingRuleSetId(), PostingRuleSetState.PUBLISHED);
        for (PostingRuleVersion oldVersion : published) {
            oldVersion.setState(PostingRuleSetState.ARCHIVED);
            oldVersion.setArchivedAt(Instant.now());
            oldVersion.setModifiedAt(Instant.now());
            versionRepository.save(oldVersion);
            log.info("Archived version: {}", oldVersion.getVersionId());
        }

        // Publish new version
        version.setState(PostingRuleSetState.PUBLISHED);
        version.setPublishedAt(Instant.now());
        version.setModifiedAt(Instant.now());
        PostingRuleVersion saved = versionRepository.save(version);
        log.info("Published version: {}", saved.getVersionId());
        return saved;
    }

    @Override
    public PostingRuleVersion archiveVersion(UUID versionId) {
        PostingRuleVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new IllegalArgumentException("Version not found: " + versionId));

        if (version.getState() != PostingRuleSetState.PUBLISHED) {
            throw new IllegalStateException("Can only archive PUBLISHED versions");
        }

        version.setState(PostingRuleSetState.ARCHIVED);
        version.setArchivedAt(Instant.now());
        version.setModifiedAt(Instant.now());
        PostingRuleVersion saved = versionRepository.save(version);
        log.info("Archived version: {}", saved.getVersionId());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostingRuleSet> listRuleSets(String organizationId) {
        // Organization filtering not supported at entity level
        // Return all rule sets - filtering should be done at service/controller layer
        // if needed
        return ruleSetRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostingRuleVersion> listVersions(UUID ruleSetId) {
        return versionRepository.findByPostingRuleSetId(ruleSetId);
    }
}
