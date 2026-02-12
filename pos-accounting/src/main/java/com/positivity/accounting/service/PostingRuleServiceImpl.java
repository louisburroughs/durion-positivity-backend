package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.PostingRuleMapper;
import com.positivity.accounting.internal.dto.PostingRuleSetCreateRequest;
import com.positivity.accounting.internal.dto.PostingRuleSetListResponse;
import com.positivity.accounting.internal.dto.PostingRuleSetResponse;
import com.positivity.accounting.internal.dto.PostingRuleVersionResponse;
import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;
import com.positivity.accounting.internal.enums.PostingRuleSetState;
import com.positivity.accounting.internal.repository.PostingRuleSetRepository;
import com.positivity.accounting.internal.repository.PostingRuleVersionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
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

    // ── DTO-based methods (used by controllers) ──────────────────────

    @Override
    public PostingRuleSetResponse createPostingRuleSetWithVersion(PostingRuleSetCreateRequest request) {
        PostingRuleSet ruleSet = PostingRuleMapper.toEntity(request);

        PostingRuleVersion version = new PostingRuleVersion();
        version.setVersionNumber(1);
        version.setState(PostingRuleSetState.DRAFT);
        version.setRulesDefinition(request.getRulesDefinition());
        version.setCreatedBy(request.getCreatedBy());
        version.setModifiedBy(request.getCreatedBy());
        version.setPostingRuleSet(ruleSet);
        ruleSet.getVersions().add(version);

        PostingRuleSet saved = createPostingRuleSet(ruleSet);
        return PostingRuleMapper.toResponse(saved);
    }

    @Override
    public PostingRuleVersionResponse publishRuleSet(UUID ruleSetId) {
        List<PostingRuleVersion> versions = listVersions(ruleSetId);
        PostingRuleVersion draftVersion = versions.stream()
                .filter(v -> v.getState() == PostingRuleSetState.DRAFT)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No DRAFT version found to publish"));

        PostingRuleVersion published = publishVersion(draftVersion.getVersionId());
        return PostingRuleMapper.toVersionResponse(published);
    }

    @Override
    public PostingRuleVersionResponse archiveRuleSet(UUID ruleSetId) {
        List<PostingRuleVersion> versions = listVersions(ruleSetId);
        PostingRuleVersion publishedVersion = versions.stream()
                .filter(v -> v.getState() == PostingRuleSetState.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No PUBLISHED version found to archive"));

        PostingRuleVersion archived = archiveVersion(publishedVersion.getVersionId());
        return PostingRuleMapper.toVersionResponse(archived);
    }

    @Override
    @Transactional(readOnly = true)
    public PostingRuleSetListResponse listRuleSetsAsResponse(int page, int size, String sort) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, sort));
        Page<PostingRuleSet> ruleSetsPage = ruleSetRepository.findAll(pageable);
        return PostingRuleMapper.toListResponse(ruleSetsPage);
    }

    @Override
    @Transactional(readOnly = true)
    public PostingRuleSetResponse getPostingRuleSetAsResponse(UUID ruleSetId) {
        PostingRuleSet ruleSet = findRuleSetById(ruleSetId);
        return PostingRuleMapper.toResponse(ruleSet);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostingRuleVersionResponse> listVersionsAsResponse(UUID ruleSetId, int page, int size) {
        List<PostingRuleVersion> versions = listVersions(ruleSetId);
        // Apply pagination
        int fromIndex = page * size;
        int toIndex = Math.min(fromIndex + size, versions.size());
        if (fromIndex >= versions.size()) {
            return List.of();
        }
        return versions.subList(fromIndex, toIndex).stream()
                .map(PostingRuleMapper::toVersionResponse)
                .toList();
    }

    @Override
    public PostingRuleSet updatePostingRuleSetFromRequest(UUID ruleSetId, PostingRuleSetCreateRequest request) {
        PostingRuleSet updates = PostingRuleMapper.toEntity(request);
        return updatePostingRuleSet(ruleSetId, updates);
    }

    // ── Entity-based methods (used by internal services) ─────────────

    @Override
    public PostingRuleSet createPostingRuleSet(PostingRuleSet ruleSet) {
        PostingRuleSet saved = ruleSetRepository.save(ruleSet);
        log.info("Created rule set: {}", saved.getName());
        return saved;
    }

    @Override
    @Transactional(readOnly = true)
    public PostingRuleSet getPostingRuleSet(UUID ruleSetId) {
        return findRuleSetById(ruleSetId);
    }

    /**
     * Internal lookup — no transactional annotation so it inherits the caller's
     * transaction context without the proxy-bypass pitfall.
     */
    private PostingRuleSet findRuleSetById(UUID ruleSetId) {
        return ruleSetRepository.findByIdWithVersions(ruleSetId)
                .orElseThrow(() -> new IllegalArgumentException("Posting rule set not found: " + ruleSetId));
    }

    @Override
    public PostingRuleSet updatePostingRuleSet(UUID ruleSetId, PostingRuleSet updates) {
        PostingRuleSet ruleSet = findRuleSetById(ruleSetId);

        // Check if ruleset has a PUBLISHED version - cannot modify published rulesets
        List<PostingRuleVersion> versions = versionRepository
                .findByPostingRuleSet_PostingRuleSetId(ruleSetId);
        boolean hasPublished = versions.stream()
                .anyMatch(v -> v.getState() == PostingRuleSetState.PUBLISHED);

        if (hasPublished) {
            throw new IllegalStateException(
                    "Cannot modify published rule set. Create a new version or archive the current one: " + ruleSetId);
        }

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
        PostingRuleSet ruleSet = findRuleSetById(ruleSetId);
        List<PostingRuleVersion> existing = versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId);
        int maxVersion = existing.stream()
                .map(PostingRuleVersion::getVersionNumber)
                .max(Integer::compareTo)
                .orElse(0);

        version.setVersionId(UUID.randomUUID());
        version.setPostingRuleSet(ruleSet); // Set child -> parent (owning side)
        version.setVersionNumber(maxVersion + 1);
        version.setState(PostingRuleSetState.DRAFT);
        version.setCreatedAt(Instant.now());
        version.setModifiedAt(Instant.now());

        PostingRuleVersion saved = versionRepository.save(version);
        log.info("Created version {} for rule set: {}",
                saved.getVersionNumber(), ruleSet.getName());
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
        List<PostingRuleVersion> published = versionRepository.findByPostingRuleSet_PostingRuleSetIdAndState(
                version.getPostingRuleSet() != null ? version.getPostingRuleSet().getPostingRuleSetId() : null,
                PostingRuleSetState.PUBLISHED);
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
    public List<PostingRuleSet> listRuleSets() {
        return ruleSetRepository.findAll();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostingRuleVersion> listVersions(UUID ruleSetId) {
        return versionRepository.findByPostingRuleSet_PostingRuleSetId(ruleSetId);
    }
}
