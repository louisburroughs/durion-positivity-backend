package com.positivity.customer.internal.service;

import com.positivity.customer.internal.dto.AssignPartyTagRequest;
import com.positivity.customer.internal.dto.PartyTagAssignmentResponse;
import com.positivity.customer.internal.dto.PartyTagResponse;
import com.positivity.customer.internal.dto.UpsertPartyTagRequest;
import com.positivity.customer.internal.entity.PartyTag;
import com.positivity.customer.internal.entity.PartyTagAssignment;
import com.positivity.customer.internal.enums.TagAssignmentSource;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PartyTagRepository;
import com.positivity.customer.service.PartyTagService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PartyTagServiceImpl implements PartyTagService {

    private static final String TAG = "Party tag";

    private final Clock clock;
    private final PartyTagRepository tagRepository;
    private final PartyTagAssignmentRepository assignmentRepository;
    private final CustomerFactPublisher factPublisher;

    @Override
    @Transactional
    public @NonNull PartyTagResponse createTag(@NonNull UpsertPartyTagRequest request) {
        String name = request.getName().trim();
        if (tagRepository.existsByNameIgnoreCase(name)) {
            throw new CrmDuplicateResourceException(TAG, name);
        }
        PartyTag tag = PartyTag.builder()
                .name(name)
                .category(trimToNull(request.getCategory()))
                .color(trimToNull(request.getColor()))
                .active(request.getActive() == null || request.getActive())
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .build();
        try {
            return toResponse(tagRepository.save(tag), 0L);
        } catch (DataIntegrityViolationException ex) {
            throw new CrmDuplicateResourceException(TAG, name);
        }
    }

    @Override
    @Transactional
    public @NonNull PartyTagResponse updateTag(@NonNull UUID tagId, @NonNull UpsertPartyTagRequest request) {
        PartyTag tag = requireTag(tagId);
        String name = request.getName().trim();
        // Uniqueness is case-insensitive, so a pure case change on the same row is allowed.
        tagRepository
                .findByNameIgnoreCase(name)
                .filter(other -> !other.getTagId().equals(tagId))
                .ifPresent(other -> {
                    throw new CrmDuplicateResourceException(TAG, name);
                });
        tag.setName(name);
        tag.setCategory(trimToNull(request.getCategory()));
        tag.setColor(trimToNull(request.getColor()));
        if (request.getActive() != null) {
            tag.setActive(request.getActive());
        }
        return toResponse(tagRepository.save(tag), assignmentRepository.countByTagId(tagId));
    }

    @Override
    @Transactional
    public void deleteTag(@NonNull UUID tagId) {
        PartyTag tag = requireTag(tagId);
        // Emit a removal fact per party before the assignments disappear, otherwise
        // downstream audience replicas silently keep a tag that no longer exists.
        List<PartyTagAssignment> assignments = assignmentRepository.findByTagId(tagId);
        assignmentRepository.deleteByTagId(tagId);
        tagRepository.delete(tag);
        assignments.forEach(assignment ->
                factPublisher.partyTagChanged(assignment.getPartyId(), tagId, tag.getName(), false, null));
        log.info("Deleted tag {} ({}) and {} assignment(s)", tag.getName(), tagId, assignments.size());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PartyTagResponse> listTags(boolean includeInactive) {
        List<PartyTag> tags = includeInactive
                ? tagRepository.findAllByOrderByNameAsc()
                : tagRepository.findAllByActiveTrueOrderByNameAsc();
        Map<UUID, Long> counts = assignmentCounts(tags);
        return tags.stream()
                .map(tag -> toResponse(tag, counts.getOrDefault(tag.getTagId(), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PartyTagResponse getTag(@NonNull UUID tagId) {
        return toResponse(requireTag(tagId), assignmentRepository.countByTagId(tagId));
    }

    @Override
    @Transactional
    public @NonNull PartyTagAssignmentResponse assignTag(
            @NonNull UUID partyId, @NonNull AssignPartyTagRequest request) {
        PartyTag tag = requireTag(request.getTagId());
        Optional<PartyTagAssignment> existing = assignmentRepository.findByPartyIdAndTagId(partyId, tag.getTagId());
        if (existing.isPresent()) {
            return toResponse(existing.get(), tag);
        }
        if (!tag.isActive()) {
            throw new IllegalArgumentException("Tag is inactive and cannot be assigned: " + tag.getName());
        }
        PartyTagAssignment assignment = PartyTagAssignment.builder()
                .partyId(partyId)
                .tagId(tag.getTagId())
                .assignedBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .assignedAt(Instant.now(clock))
                .source(request.getSource() != null ? request.getSource() : TagAssignmentSource.MANUAL)
                .build();
        PartyTagAssignment saved;
        try {
            saved = assignmentRepository.saveAndFlush(assignment);
        } catch (DataIntegrityViolationException ex) {
            // Concurrent assign of the same (party, tag): the unique constraint won, so
            // converge on the row that landed rather than surfacing a conflict.
            return assignmentRepository
                    .findByPartyIdAndTagId(partyId, tag.getTagId())
                    .map(found -> toResponse(found, tag))
                    .orElseThrow(() -> ex);
        }
        factPublisher.partyTagChanged(
                partyId, tag.getTagId(), tag.getName(), true, saved.getSource().name());
        return toResponse(saved, tag);
    }

    @Override
    @Transactional
    public void removeTag(@NonNull UUID partyId, @NonNull UUID tagId) {
        Optional<PartyTagAssignment> existing = assignmentRepository.findByPartyIdAndTagId(partyId, tagId);
        if (existing.isEmpty()) {
            return;
        }
        assignmentRepository.delete(existing.get());
        tagRepository
                .findById(tagId)
                .ifPresent(tag -> factPublisher.partyTagChanged(partyId, tagId, tag.getName(), false, null));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PartyTagAssignmentResponse> listPartyTags(@NonNull UUID partyId) {
        List<PartyTagAssignment> assignments = assignmentRepository.findByPartyId(partyId);
        if (assignments.isEmpty()) {
            return List.of();
        }
        Map<UUID, PartyTag> tags =
                tagRepository
                        .findAllByTagIdIn(assignments.stream()
                                .map(PartyTagAssignment::getTagId)
                                .toList())
                        .stream()
                        .collect(Collectors.toMap(PartyTag::getTagId, Function.identity()));
        return assignments.stream()
                .filter(assignment -> tags.containsKey(assignment.getTagId()))
                .map(assignment -> toResponse(assignment, tags.get(assignment.getTagId())))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<UUID> findPartyIdsByTags(@NonNull List<UUID> tagIds, boolean matchAll) {
        if (tagIds.isEmpty()) {
            return List.of();
        }
        List<UUID> distinct = tagIds.stream().distinct().toList();
        return matchAll
                ? assignmentRepository.findPartyIdsHavingAllTags(distinct, distinct.size())
                : assignmentRepository.findPartyIdsHavingAnyTag(distinct);
    }

    private Map<UUID, Long> assignmentCounts(List<PartyTag> tags) {
        return tags.stream()
                .collect(
                        Collectors.toMap(PartyTag::getTagId, tag -> assignmentRepository.countByTagId(tag.getTagId())));
    }

    private PartyTag requireTag(UUID tagId) {
        return tagRepository.findById(tagId).orElseThrow(() -> new CrmResourceNotFoundException(TAG, tagId));
    }

    private static PartyTagResponse toResponse(PartyTag tag, long assignmentCount) {
        return new PartyTagResponse(
                tag.getTagId(),
                tag.getName(),
                tag.getCategory(),
                tag.getColor(),
                tag.isActive(),
                assignmentCount,
                tag.getCreatedAt());
    }

    private static PartyTagAssignmentResponse toResponse(PartyTagAssignment assignment, PartyTag tag) {
        return new PartyTagAssignmentResponse(
                assignment.getAssignmentId(),
                assignment.getPartyId(),
                assignment.getTagId(),
                tag.getName(),
                tag.getCategory(),
                assignment.getSource().name(),
                assignment.getAssignedBy(),
                assignment.getAssignedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
