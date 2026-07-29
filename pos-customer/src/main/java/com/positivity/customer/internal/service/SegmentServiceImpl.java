package com.positivity.customer.internal.service;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.domain.SegmentPredicateValidator;
import com.positivity.customer.internal.dto.SegmentMembersRequest;
import com.positivity.customer.internal.dto.SegmentResolutionResponse;
import com.positivity.customer.internal.dto.SegmentResponse;
import com.positivity.customer.internal.dto.UpsertSegmentRequest;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.entity.SegmentMember;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingChannel;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.exception.CrmDuplicateResourceException;
import com.positivity.customer.internal.exception.CrmResourceNotFoundException;
import com.positivity.customer.internal.exception.CrmUnprocessableEntityException;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.SegmentRepository;
import com.positivity.customer.service.MarketingConsentService;
import com.positivity.customer.service.SegmentService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class SegmentServiceImpl implements SegmentService {

    private static final String SEGMENT = "Segment";
    private static final int MAX_SAMPLE_SIZE = 25;

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final SegmentRepository segmentRepository;
    private final SegmentMemberRepository segmentMemberRepository;
    private final SegmentResolutionService resolutionService;
    private final MarketingConsentService marketingConsentService;
    private final CustomerFactPublisher factPublisher;

    @Override
    @Transactional
    public @NonNull SegmentResponse create(@NonNull UpsertSegmentRequest request) {
        String name = request.getName().trim();
        if (segmentRepository.existsByNameIgnoreCase(name)) {
            throw new CrmDuplicateResourceException(SEGMENT, name);
        }
        Segment segment = Segment.builder()
                .name(name)
                .description(request.getDescription())
                .audienceType(request.getAudienceType())
                .type(request.getType())
                .predicate(validatedPredicateJson(request))
                .active(request.getActive() == null || request.getActive())
                .createdBy(SecurityContextHelper.getCurrentUsernameOrDefault("system"))
                .build();
        Segment saved = segmentRepository.save(segment);
        publishSegment(saved, false);
        return toResponse(saved, 0L);
    }

    @Override
    @Transactional
    public @NonNull SegmentResponse update(@NonNull UUID segmentId, @NonNull UpsertSegmentRequest request) {
        Segment segment = requireSegment(segmentId);
        String name = request.getName().trim();
        segmentRepository
                .findByNameIgnoreCase(name)
                .filter(other -> !other.getSegmentId().equals(segmentId))
                .ifPresent(other -> {
                    throw new CrmDuplicateResourceException(SEGMENT, name);
                });
        if (request.getType() != segment.getType()) {
            // Switching kind would strand the other kind's state — pinned members on a segment
            // that now computes membership, or a predicate on one that no longer evaluates it.
            throw new CrmUnprocessableEntityException(
                    "Segment type cannot change after creation; create a new segment instead");
        }
        if (request.getAudienceType() != segment.getAudienceType()) {
            throw new CrmUnprocessableEntityException(
                    "Segment audienceType cannot change after creation; create a new segment instead");
        }
        segment.setName(name);
        segment.setDescription(request.getDescription());
        segment.setPredicate(validatedPredicateJson(request));
        if (request.getActive() != null) {
            segment.setActive(request.getActive());
        }
        Segment saved = segmentRepository.save(segment);
        publishSegment(saved, false);
        return toResponse(saved, memberCount(saved));
    }

    @Override
    @Transactional
    public void delete(@NonNull UUID segmentId) {
        Segment segment = requireSegment(segmentId);
        segmentMemberRepository.deleteBySegmentId(segmentId);
        segmentRepository.delete(segment);
        publishSegment(segment, true);
        log.info("Deleted segment {} ({})", segment.getName(), segmentId);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SegmentResponse get(@NonNull UUID segmentId) {
        Segment segment = requireSegment(segmentId);
        return toResponse(segment, memberCount(segment));
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<SegmentResponse> list(@Nullable AudienceType audienceType) {
        List<Segment> segments = audienceType == null
                ? segmentRepository.findAllByOrderByNameAsc()
                : segmentRepository.findByAudienceTypeOrderByNameAsc(audienceType);
        return segments.stream()
                .map(segment -> toResponse(segment, memberCount(segment)))
                .toList();
    }

    @Override
    @Transactional
    public @NonNull SegmentResponse addMembers(@NonNull UUID segmentId, @NonNull SegmentMembersRequest request) {
        Segment segment = requireSegment(segmentId);
        if (segment.getType() != SegmentType.STATIC) {
            throw new CrmUnprocessableEntityException(
                    "Members can only be pinned on a STATIC segment; this segment is DYNAMIC");
        }
        String actor = SecurityContextHelper.getCurrentUsernameOrDefault("system");
        Instant now = Instant.now(clock);
        List<SegmentMember> toAdd = request.getPartyIds().stream()
                .distinct()
                .filter(partyId -> segmentMemberRepository
                        .findBySegmentIdAndPartyId(segmentId, partyId)
                        .isEmpty())
                .map(partyId -> SegmentMember.builder()
                        .segmentId(segmentId)
                        .partyId(partyId)
                        .addedBy(actor)
                        .addedAt(now)
                        .build())
                .toList();
        if (!toAdd.isEmpty()) {
            segmentMemberRepository.saveAll(toAdd);
        }
        return toResponse(segment, memberCount(segment));
    }

    @Override
    @Transactional
    public void removeMember(@NonNull UUID segmentId, @NonNull UUID partyId) {
        segmentMemberRepository
                .findBySegmentIdAndPartyId(segmentId, partyId)
                .ifPresent(segmentMemberRepository::delete);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull SegmentResolutionResponse resolve(
            @NonNull UUID segmentId, @Nullable MarketingChannel channel, int sampleSize) {
        Segment segment = requireSegment(segmentId);
        SegmentResolutionService.Resolution resolution = resolutionService.resolve(segment, parsedPredicate(segment));
        List<UUID> matched = resolution.partyIds();

        Long eligible = channel == null
                ? null
                : matched.stream()
                        .filter(partyId -> marketingConsentService
                                .resolveEligibility(partyId, channel)
                                .allowed())
                        .count();

        int cappedSample = Math.clamp(sampleSize, 0, MAX_SAMPLE_SIZE);
        List<UUID> sampleIds = matched.stream().limit(cappedSample).toList();
        Map<UUID, String> labels = maskedLabels(segment.getAudienceType(), sampleIds);

        return new SegmentResolutionResponse(
                segmentId,
                segment.getAudienceType().name(),
                matched.size(),
                eligible,
                channel != null ? channel.name() : null,
                resolution.truncated(),
                sampleIds.stream()
                        .map(partyId -> new SegmentResolutionResponse.SegmentSampleEntry(partyId, labels.get(partyId)))
                        .toList());
    }

    @Override
    @Transactional
    public @NonNull Optional<Integer> resolveAndPublish(@NonNull UUID requestId, @NonNull UUID segmentId) {
        Optional<Segment> found = segmentRepository.findById(segmentId);
        if (found.isEmpty()) {
            // A request naming a segment that has since been deleted is not worth retrying; the
            // requester will time out and stop asking.
            log.info("Segment {} no longer exists; not answering resolve request {}", segmentId, requestId);
            return Optional.empty();
        }
        Segment segment = found.get();
        SegmentResolutionService.Resolution resolution = resolutionService.resolve(segment, parsedPredicate(segment));
        factPublisher.segmentResolved(
                requestId, segmentId, segment.getAudienceType().name(), resolution.partyIds(), resolution.truncated());
        return Optional.of(resolution.partyIds().size());
    }

    private void publishSegment(Segment segment, boolean deleted) {
        factPublisher.segmentChanged(
                segment.getSegmentId(),
                segment.getName(),
                segment.getAudienceType().name(),
                segment.getType().name(),
                segment.isActive(),
                deleted);
    }

    private Map<UUID, String> maskedLabels(AudienceType audienceType, List<UUID> partyIds) {
        Map<UUID, String> labels = new HashMap<>();
        for (PartyAttributes party : resolutionService.loadAttributes(audienceType, partyIds)) {
            labels.put(party.partyId(), mask(party.displayLabel()));
        }
        return labels;
    }

    /**
     * Enough of the name to recognize a mis-scoped audience, not enough to be a contact list.
     */
    private static @Nullable String mask(@Nullable String label) {
        if (label == null || label.isBlank()) {
            return null;
        }
        String trimmed = label.trim();
        int keep = Math.min(5, trimmed.length());
        return trimmed.length() <= keep ? trimmed : trimmed.substring(0, keep) + "***";
    }

    /**
     * Validate then serialize. STATIC segments must not carry a predicate and DYNAMIC ones must:
     * either mismatch would produce a segment whose stored definition does not describe its
     * membership.
     */
    private @Nullable String validatedPredicateJson(UpsertSegmentRequest request) {
        if (request.getType() == SegmentType.STATIC) {
            if (request.getPredicate() != null) {
                throw new CrmUnprocessableEntityException(
                        "A STATIC segment takes an explicit member list, not a predicate");
            }
            return null;
        }
        SegmentPredicate predicate = request.getPredicate();
        if (predicate == null) {
            throw new CrmUnprocessableEntityException("A DYNAMIC segment requires a predicate");
        }
        SegmentPredicateValidator.validate(predicate, request.getAudienceType());
        return objectMapper.writeValueAsString(predicate);
    }

    private Optional<SegmentPredicate> parsedPredicate(Segment segment) {
        if (segment.getPredicate() == null || segment.getPredicate().isBlank()) {
            return Optional.empty();
        }
        return Optional.of(objectMapper.readValue(segment.getPredicate(), SegmentPredicate.class));
    }

    private Long memberCount(Segment segment) {
        return segment.getType() == SegmentType.STATIC
                ? segmentMemberRepository.countBySegmentId(segment.getSegmentId())
                : null;
    }

    private Segment requireSegment(UUID segmentId) {
        return segmentRepository
                .findById(segmentId)
                .orElseThrow(() -> new CrmResourceNotFoundException(SEGMENT, segmentId));
    }

    private SegmentResponse toResponse(Segment segment, Long memberCount) {
        return new SegmentResponse(
                segment.getSegmentId(),
                segment.getName(),
                segment.getDescription(),
                segment.getAudienceType().name(),
                segment.getType().name(),
                parsedPredicate(segment).orElse(null),
                segment.isActive(),
                memberCount,
                segment.getCreatedAt(),
                segment.getUpdatedAt());
    }
}
