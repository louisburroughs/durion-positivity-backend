package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.positivity.customer.internal.enums.ContactPointType;
import com.positivity.customer.internal.enums.PreferredContactMethod;

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

    @Schema(description = "Unique identifier of the person", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID personId;

    @Schema(description = "First name", example = "John")
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    private String lastName;

    @Schema(description = "Display name", example = "John Doe")
    private String displayName;

    @Schema(description = "Preferred contact method", example = "EMAIL")
    private PreferredContactMethod preferredContactMethod;

    @Schema(description = "Contact points (emails, phones)")
    private List<ContactPointDto> contactPoints;

    @Schema(description = "Whether this CRM record represents an individual customer", example = "true")
    private boolean individualCustomer;

    @Schema(description = "Whether this person is an active contact on one or more commercial accounts", example = "false")
    private boolean commercialContact;

    @Schema(description = "Number of active commercial accounts where this person is a contact", example = "0")
    private int commercialAccountCount;

    @Schema(description = "Timestamp when the person was created")
    private Instant createdAt;

    @Schema(description = "Timestamp when the person was last updated")
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
        @Schema(description = "Contact point ID")
        private UUID contactPointId;

        @Schema(description = "Type of contact point", example = "EMAIL")
        private ContactPointType contactType;

        @Schema(description = "Contact value", example = "john.doe@example.com")
        private String value;

        @Schema(description = "Whether this is the primary contact of its type")
        private boolean isPrimary;
    }
}
