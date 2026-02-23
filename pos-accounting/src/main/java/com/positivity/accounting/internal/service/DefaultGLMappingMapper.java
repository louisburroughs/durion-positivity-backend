package com.positivity.accounting.internal.service;

import org.jspecify.annotations.NonNull;

import com.positivity.accounting.internal.dto.DefaultGLMappingRequest;
import com.positivity.accounting.internal.dto.DefaultGLMappingResponse;
import com.positivity.accounting.internal.entity.DefaultGLMapping;
import com.positivity.accounting.internal.entity.GLAccount;

/**
 * Mapper for DefaultGLMapping entity to DTO conversion.
 */
public final class DefaultGLMappingMapper {

    private DefaultGLMappingMapper() {
        // Utility class
    }

    /**
     * Converts a DefaultGLMapping entity to a response DTO.
     * 
     * @param mapping       the entity
     * @param debitAccount  the debit GL account (for denormalized fields)
     * @param creditAccount the credit GL account (for denormalized fields)
     * @return the response DTO
     */
    public static DefaultGLMappingResponse toResponse(@NonNull DefaultGLMapping mapping,
            GLAccount debitAccount, GLAccount creditAccount) {
        return DefaultGLMappingResponse.builder()
                .mappingId(mapping.getMappingId())
                .eventType(mapping.getEventType())
                .organizationId(mapping.getOrganizationId())
                .debitAccountId(mapping.getDebitAccountId())
                .debitAccountCode(debitAccount != null ? debitAccount.getAccountCode() : null)
                .debitAccountName(debitAccount != null ? debitAccount.getAccountName() : null)
                .creditAccountId(mapping.getCreditAccountId())
                .creditAccountCode(creditAccount != null ? creditAccount.getAccountCode() : null)
                .creditAccountName(creditAccount != null ? creditAccount.getAccountName() : null)
                .description(mapping.getDescription())
                .active(mapping.getActive())
                .createdAt(mapping.getCreatedAt())
                .createdBy(mapping.getCreatedBy())
                .modifiedAt(mapping.getModifiedAt())
                .modifiedBy(mapping.getModifiedBy())
                .build();
    }

    /**
     * Converts a request DTO to a DefaultGLMapping entity.
     * 
     * @param request the request DTO
     * @return the entity
     */
    public static DefaultGLMapping toEntity(@NonNull DefaultGLMappingRequest request) {
        DefaultGLMapping mapping = new DefaultGLMapping();
        mapping.setEventType(request.getEventType());
        mapping.setOrganizationId(request.getOrganizationId());
        mapping.setDebitAccountId(request.getDebitAccountId());
        mapping.setCreditAccountId(request.getCreditAccountId());
        mapping.setDescription(request.getDescription());
        mapping.setActive(request.getActive() != null ? request.getActive() : true);
        return mapping;
    }

    /**
     * Updates an existing entity with data from a request DTO.
     * 
     * @param entity  the entity to update
     * @param request the request DTO
     */
    public static void updateEntity(@NonNull DefaultGLMapping entity, @NonNull DefaultGLMappingRequest request) {
        entity.setEventType(request.getEventType());
        entity.setOrganizationId(request.getOrganizationId());
        entity.setDebitAccountId(request.getDebitAccountId());
        entity.setCreditAccountId(request.getCreditAccountId());
        entity.setDescription(request.getDescription());
        if (request.getActive() != null) {
            entity.setActive(request.getActive());
        }
    }
}
