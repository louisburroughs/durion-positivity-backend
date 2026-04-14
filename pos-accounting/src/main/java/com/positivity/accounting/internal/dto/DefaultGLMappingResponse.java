package com.positivity.accounting.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * DTO for default GL account mapping response.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide - DefaultGLMapping Response</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DefaultGLMappingResponse {

    private UUID mappingId;
    private String eventType;

    @Nullable
    private UUID organizationId;

    private UUID debitAccountId;
    private String debitAccountCode;
    private String debitAccountName;

    private UUID creditAccountId;
    private String creditAccountCode;
    private String creditAccountName;

    private String description;
    private Boolean active;

    private Instant createdAt;
    private String createdBy;
    private Instant modifiedAt;
    private String modifiedBy;
}
