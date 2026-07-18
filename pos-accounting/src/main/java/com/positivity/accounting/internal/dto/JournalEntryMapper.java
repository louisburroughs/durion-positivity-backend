package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.math.BigDecimal;
import java.util.ArrayList;
import org.jspecify.annotations.NonNull;

/**
 * Mapper for converting between JournalEntry entities and DTOs.
 */
public final class JournalEntryMapper {

    private JournalEntryMapper() {
        // Utility class
    }

    /**
     * Converts a JournalEntry entity to a response DTO.
     */
    public static JournalEntryResponse toResponse(@NonNull JournalEntry entity) {
        JournalEntryResponse response = new JournalEntryResponse();
        response.setJournalEntryId(entity.getJournalEntryId());
        response.setEntryNumber(entity.getEntryNumber());
        response.setStatus(entity.getStatus());
        response.setTransactionDate(entity.getTransactionDate());
        response.setDescription(entity.getDescription());
        response.setSourceEventId(entity.getSourceEventId());
        response.setSourceEventType(entity.getSourceEventType());
        response.setPostingRuleSetId(entity.getPostingRuleSetId());
        response.setPostingRuleVersionId(entity.getPostingRuleVersionId());
        response.setReversalJournalEntryId(entity.getReversalJournalEntryId());
        response.setReversedByJournalEntryId(entity.getReversedByJournalEntryId());
        response.setCreatedAt(entity.getCreatedAt());
        response.setCreatedBy(entity.getCreatedBy());
        response.setModifiedAt(entity.getUpdatedAt());
        response.setModifiedBy(entity.getModifiedBy());
        response.setPostedAt(entity.getPostedAt());
        response.setPostedBy(entity.getPostedBy());
        response.setReversedAt(entity.getReversedAt());

        if (entity.getLines() != null) {
            response.setLines(entity.getLines().stream()
                    .map(JournalEntryMapper::toLineResponse)
                    .toList());

            // Calculate totals
            BigDecimal totalDebits = entity.getLines().stream()
                    .map(JournalEntryLine::getDebitAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredits = entity.getLines().stream()
                    .map(JournalEntryLine::getCreditAmount)
                    .filter(java.util.Objects::nonNull)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            response.setTotalDebits(totalDebits);
            response.setTotalCredits(totalCredits);
            response.setIsBalanced(totalDebits.compareTo(totalCredits) == 0);
        }

        return response;
    }

    /**
     * Converts a request DTO to a JournalEntry entity for creation.
     */
    public static JournalEntry toEntity(@NonNull JournalEntryCreateRequest request) {
        JournalEntry entity = new JournalEntry();
        entity.setTransactionDate(request.getTransactionDate());
        entity.setDescription(request.getDescription());
        entity.setSourceEventId(request.getSourceEventId());
        entity.setSourceEventType(request.getSourceEventType());
        entity.setPostingRuleSetId(request.getPostingRuleSetId());
        entity.setPostingRuleVersionId(request.getPostingRuleVersionId());

        if (request.getLines() != null) {
            entity.setLines(new ArrayList<>(request.getLines().stream()
                    .map(JournalEntryMapper::toLineEntity)
                    .toList()));
        }

        return entity;
    }

    private static JournalEntryResponse.JournalEntryLineResponse toLineResponse(@NonNull JournalEntryLine line) {
        JournalEntryResponse.JournalEntryLineResponse response = new JournalEntryResponse.JournalEntryLineResponse();
        response.setLineNumber(line.getLineNumber());
        response.setGlAccountId(line.getGlAccountId());
        response.setDebitAmount(line.getDebitAmount());
        response.setCreditAmount(line.getCreditAmount());
        response.setDescription(line.getDescription());
        response.setDimensions(line.getDimensions());
        return response;
    }

    private static JournalEntryLine toLineEntity(JournalEntryCreateRequest.@NonNull JournalEntryLineRequest request) {
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(request.getGlAccountId());
        line.setDebitAmount(request.getDebitAmount());
        line.setCreditAmount(request.getCreditAmount());
        line.setDescription(request.getDescription());
        line.setDimensions(request.getDimensions());
        return line;
    }
}
