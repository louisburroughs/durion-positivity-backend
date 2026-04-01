package com.positivity.workorder.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.workorder.internal.dto.pick.CreateSubstituteLinkRequest;
import com.positivity.workorder.internal.dto.pick.SubstituteLinkResponse;
import com.positivity.workorder.internal.dto.pick.UpdateSubstituteLinkRequest;
import com.positivity.workorder.internal.entity.SubstituteAudit;
import com.positivity.workorder.internal.entity.SubstituteLink;
import com.positivity.workorder.internal.exception.DuplicateSubstituteLinkException;
import com.positivity.workorder.internal.exception.SubstituteLinkNotFoundException;
import com.positivity.workorder.internal.exception.StaleSubstituteLinkVersionException;
import com.positivity.workorder.internal.repository.SubstituteAuditRepository;
import com.positivity.workorder.internal.repository.SubstituteLinkRepository;
import com.positivity.workorder.service.SubstituteLinkService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class SubstituteLinkServiceImpl implements SubstituteLinkService {

    private static final String SYSTEM_ACTOR = "system";

    private final SubstituteLinkRepository substituteLinkRepository;
    private final SubstituteAuditRepository substituteAuditRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SubstituteLinkServiceImpl(
            SubstituteLinkRepository substituteLinkRepository,
            SubstituteAuditRepository substituteAuditRepository,
            ObjectMapper objectMapper,
            Clock clock) {
        this.substituteLinkRepository = substituteLinkRepository;
        this.substituteAuditRepository = substituteAuditRepository;
        this.objectMapper = Objects.requireNonNullElseGet(objectMapper, ObjectMapper::new);
        this.clock = Objects.requireNonNullElseGet(clock, Clock::systemUTC);
    }

    @Override
    @Transactional
    public SubstituteLinkResponse createLink(@NonNull CreateSubstituteLinkRequest request) {
        if (substituteLinkRepository.existsByProductIdAndSubstitutePartId(
                request.getProductId(), request.getSubstitutePartId())) {
            throw new DuplicateSubstituteLinkException(request.getProductId(), request.getSubstitutePartId());
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);

        SubstituteLink newLink = SubstituteLink.builder()
                .productId(request.getProductId())
                .substitutePartId(request.getSubstitutePartId())
                .substituteType(request.getSubstituteType())
                .isAutoSuggest(Boolean.TRUE.equals(request.getIsAutoSuggest()))
                .priority(request.getPriority() != null ? request.getPriority() : 100)
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .isActive(true)
                .createdBy(actor)
                .build();

        SubstituteLink saved = substituteLinkRepository.save(newLink);

        substituteAuditRepository.save(SubstituteAudit.builder()
                .link(saved)
                .operation("CREATE")
                .payloadBefore(null)
                .payloadAfter(toJson(saved))
                .actorId(actor)
                .timestamp(Instant.now(clock))
                .build());

        return toResponse(saved);
    }

    @Override
    @Transactional
    public SubstituteLinkResponse updateLink(@NonNull UUID linkId, @NonNull UpdateSubstituteLinkRequest request) {
        SubstituteLink existing = substituteLinkRepository.findById(linkId)
                .orElseThrow(() -> new SubstituteLinkNotFoundException(linkId));

        if (request.getVersion() != existing.getVersion()) {
            throw new StaleSubstituteLinkVersionException(linkId, request.getVersion(), existing.getVersion());
        }

        String before = toJson(existing);

        if (request.getSubstituteType() != null) {
            existing.setSubstituteType(request.getSubstituteType());
        }
        if (request.getPriority() != null) {
            existing.setPriority(request.getPriority());
        }
        if (request.getIsAutoSuggest() != null) {
            existing.setAutoSuggest(request.getIsAutoSuggest());
        }
        if (request.getEffectiveFrom() != null) {
            existing.setEffectiveFrom(request.getEffectiveFrom());
        }
        if (request.getEffectiveTo() != null) {
            existing.setEffectiveTo(request.getEffectiveTo());
        }

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        existing.setUpdatedBy(actor);

        SubstituteLink saved = substituteLinkRepository.save(existing);

        substituteAuditRepository.save(SubstituteAudit.builder()
                .link(saved)
                .operation("UPDATE")
                .payloadBefore(before)
                .payloadAfter(toJson(saved))
                .actorId(actor)
                .timestamp(Instant.now(clock))
                .build());

        return toResponse(saved);
    }

    @Override
    @Transactional
    public SubstituteLinkResponse deleteLink(@NonNull UUID productId, @NonNull UUID substitutePartId) {
        SubstituteLink existing = substituteLinkRepository
                .findByProductIdAndSubstitutePartId(productId, substitutePartId)
                .orElseThrow(() -> new SubstituteLinkNotFoundException(productId, substitutePartId));

        String before = toJson(existing);

        String actor = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        existing.setIsActive(false);
        existing.setUpdatedBy(actor);

        SubstituteLink saved = substituteLinkRepository.save(existing);

        substituteAuditRepository.save(SubstituteAudit.builder()
                .link(saved)
                .operation("DELETE")
                .payloadBefore(before)
                .payloadAfter(toJson(saved))
                .actorId(actor)
                .timestamp(Instant.now(clock))
                .build());

        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubstituteLinkResponse> listLinks(@NonNull UUID productId) {
        return substituteLinkRepository.findByProductIdAndIsActiveTrueOrderByPriorityAsc(productId)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SubstituteLinkResponse> suggestSubstitutes(@NonNull UUID workorderId) {
        // Stub: full implementation requires WorkorderService to look up workorder
        // line items. Returns empty list until WorkorderService integration is added
        // in a future story.
        return List.of();
    }

    private SubstituteLinkResponse toResponse(@NonNull SubstituteLink link) {
        return SubstituteLinkResponse.builder()
                .id(link.getId())
                .productId(link.getProductId())
                .substitutePartId(link.getSubstitutePartId())
                .substituteType(link.getSubstituteType())
                .priority(link.getPriority())
                .isAutoSuggest(link.isAutoSuggest())
                .isActive(Boolean.TRUE.equals(link.getIsActive()))
                .version(link.getVersion())
                .createdBy(link.getCreatedBy())
                .updatedBy(link.getUpdatedBy())
                .build();
    }

    private String toJson(@NonNull Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
