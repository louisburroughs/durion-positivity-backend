package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.enums.PreferredContactMethod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response DTO for person creation.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response after creating a person record")
public class CreatePersonResponse {

    @NotNull
    @Schema(
            description = "Unique identifier of the created person",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID personId;

    @NotNull
    @Schema(description = "First name", example = "John", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotNull
    @Schema(description = "Last name", example = "Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @NotNull
    @Schema(description = "Preferred contact method", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private PreferredContactMethod preferredContactMethod;

    @Schema(
            description = "Number of contact points created",
            example = "2",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private int contactPointsCreated;

    @NotNull
    @Schema(
            description = "Timestamp when the person was created",
            example = "2026-01-01T12:00:00Z",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Instant createdAt;

    /**
     * Creates a response from a PersonParty entity. Names are supplied by the
     * caller (the request), since pos-customer no longer stores a local name copy
     * — identity lives in pos-people (ADR-0015 I2, issue #684).
     *
     * @param person             the created person-party
     * @param firstName          first name (from the create request)
     * @param lastName           last name (from the create request)
     * @param contactPointsCount number of contact points created
     * @return the response DTO
     */
    public static CreatePersonResponse from(
            PersonParty person, String firstName, String lastName, int contactPointsCount) {
        return CreatePersonResponse.builder()
                .personId(person.getPersonId())
                .firstName(firstName)
                .lastName(lastName)
                .preferredContactMethod(person.getPreferredContactMethod())
                .contactPointsCreated(contactPointsCount)
                .createdAt(person.getCreatedAt())
                .build();
    }
}
