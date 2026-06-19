package com.positivity.securityservice.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Person record returned by the CRM customer person-search integration")
public record CustomerPersonSearchResponse(
        @Schema(description = "Person identifier", example = "01960003-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID personId,
        @Schema(description = "Given name", example = "Jane", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String firstName,
        @Schema(description = "Family name", example = "Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String lastName,
        @Schema(description = "Display name", example = "Jane Smith", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String displayName,
        @Schema(description = "Known contact points for the person", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        List<ContactPointDto> contactPoints,
        @Schema(description = "True when the person is an individual customer", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean individualCustomer,
        @Schema(description = "True when the person is a commercial contact", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean commercialContact,
        @Schema(description = "Number of commercial accounts the person is linked to", example = "0", requiredMode = Schema.RequiredMode.REQUIRED)
        int commercialAccountCount,
        @Schema(description = "Creation timestamp", example = "2026-01-15T09:30:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant createdAt,
        @Schema(description = "Last-updated timestamp", example = "2026-01-16T11:00:00Z", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant updatedAt) {

    @Schema(description = "A single contact point belonging to a person")
    public record ContactPointDto(
            @Schema(description = "Contact point identifier", example = "01960003-0000-7000-8000-000000000080", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID contactPointId,
            @Schema(description = "Contact type", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
            String contactType,
            @Schema(description = "Contact value", example = "jane.smith@example.com", requiredMode = Schema.RequiredMode.REQUIRED)
            String value,
            @Schema(description = "True when this is the primary contact point of its type", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
            boolean isPrimary) {}
}
