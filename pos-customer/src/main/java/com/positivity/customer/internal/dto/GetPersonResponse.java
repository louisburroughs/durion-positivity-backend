package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for retrieving person details.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Person details response")
public class GetPersonResponse {

    @Schema(
            description = "Unique identifier of the person",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID personId;

    @Schema(description = "First name", example = "John", requiredMode = REQUIRED)
    private String firstName;

    @Schema(description = "Last name", example = "Doe", requiredMode = REQUIRED)
    private String lastName;

    @Schema(description = "Display name", example = "John Doe", requiredMode = NOT_REQUIRED)
    private String displayName;

    @Schema(
            description = "Preferred contact method",
            example = "EMAIL",
            requiredMode = NOT_REQUIRED)
    private PreferredContactMethod preferredContactMethod;

    @Schema(description = "Contact points (emails, phones)", requiredMode = NOT_REQUIRED)
    @Valid
    private List<ContactPointDto> contactPoints;

    @Schema(
            description = "Whether this CRM record represents an individual customer",
            example = "true",
            requiredMode = REQUIRED)
    private boolean individualCustomer;

    @Schema(
            description = "Whether this person is an active contact on one or more commercial accounts",
            example = "false",
            requiredMode = REQUIRED)
    private boolean commercialContact;

    @Schema(
            description = "Number of active commercial accounts where this person is a contact",
            example = "0",
            requiredMode = REQUIRED)
    private int commercialAccountCount;

    @Schema(
            description = "Timestamp when the person was created",
            example = "2026-01-15T10:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the person was last updated",
            example = "2026-02-20T14:45:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;

    /**
     * Contact point DTO for response.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Contact point details")
    public static class ContactPointDto {
        @Schema(
                description = "Contact point ID",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        @NotNull
        private UUID contactPointId;

        @Schema(
                description = "Type of contact point",
                example = "EMAIL",
                requiredMode = REQUIRED)
        @NotNull
        private ContactPointType contactType;

        @Schema(
                description = "Contact value",
                example = "john.doe@example.com",
                requiredMode = REQUIRED)
        private String value;

        @Schema(
                description = "Whether this is the primary contact of its type",
                example = "true",
                requiredMode = REQUIRED)
        private boolean isPrimary;
    }
}
