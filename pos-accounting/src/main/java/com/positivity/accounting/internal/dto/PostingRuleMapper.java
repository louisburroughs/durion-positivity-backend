package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.PostingRuleSet;
import com.positivity.accounting.internal.entity.PostingRuleVersion;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Mapper for converting between Posting Rule entities and DTOs.
 */
public final class PostingRuleMapper {

    private PostingRuleMapper() {
        // Utility class
    }

    /**
     * Convert PostingRuleSet entity to response DTO.
     */
    public static PostingRuleSetResponse toResponse(PostingRuleSet entity) {
        if (entity == null) {
            return null;
        }

        List<PostingRuleVersionResponse> versions = entity.getVersions() != null
                ? entity.getVersions().stream()
                        .map(PostingRuleMapper::toVersionResponse)
                        .collect(Collectors.toList())
                : Collections.emptyList();

        return PostingRuleSetResponse.builder()
                .postingRuleSetId(entity.getPostingRuleSetId())
                .name(entity.getName())
                .eventType(entity.getEventType())
                .description(entity.getDescription())
                .versions(versions)
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .modifiedAt(entity.getModifiedAt())
                .modifiedBy(entity.getModifiedBy())
                .build();
    }

    /**
     * Convert PostingRuleVersion entity to response DTO.
     */
    public static PostingRuleVersionResponse toVersionResponse(PostingRuleVersion entity) {
        if (entity == null) {
            return null;
        }

        return PostingRuleVersionResponse.builder()
                .versionId(entity.getVersionId())
                .postingRuleSetId(
                        entity.getPostingRuleSet() != null ? entity.getPostingRuleSet().getPostingRuleSetId() : null)
                .versionNumber(entity.getVersionNumber())
                .state(entity.getState())
                .rulesDefinition(entity.getRulesDefinition())
                .createdAt(entity.getCreatedAt())
                .createdBy(entity.getCreatedBy())
                .modifiedAt(entity.getModifiedAt())
                .modifiedBy(entity.getModifiedBy())
                .build();
    }

    /**
     * Convert create request to PostingRuleSet entity (basic fields only).
     */
    public static PostingRuleSet toEntity(PostingRuleSetCreateRequest request) {
        if (request == null) {
            return null;
        }

        PostingRuleSet entity = new PostingRuleSet();
        entity.setName(request.getName());
        entity.setEventType(request.getEventType());
        entity.setDescription(request.getDescription());
        entity.setCreatedBy(request.getCreatedBy());
        entity.setModifiedBy(request.getCreatedBy());
        return entity;
    }

    /**
     * Convert list of entities to list response DTO.
     */
    public static PostingRuleSetListResponse toListResponse(List<PostingRuleSet> entities,
            int page, int size) {
        List<PostingRuleSetResponse> responses = entities.stream()
                .map(PostingRuleMapper::toResponse)
                .collect(Collectors.toList());

        int totalElements = entities.size();
        int totalPages = (int) Math.ceil((double) totalElements / size);

        return PostingRuleSetListResponse.builder()
                .ruleSets(responses)
                .totalElements(totalElements)
                .totalPages(totalPages)
                .currentPage(page)
                .pageSize(size)
                .build();
    }
}
