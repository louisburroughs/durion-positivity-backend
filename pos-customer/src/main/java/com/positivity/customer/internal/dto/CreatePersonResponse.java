package com.positivity.customer.internal.dto;

import java.time.Instant;
import java.util.UUID;

import com.positivity.customer.internal.entity.Person;

import io.swagger.v3.oas.annotations.media.Schema;
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

    @Schema(description = "Unique identifier of the created person", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID personId;

    @Schema(description = "First name", example = "John")
    private String firstName;

    @Schema(description = "Last name", example = "Doe")
    private String lastName;

    @Schema(description = "Preferred contact method", example = "EMAIL")
    private PreferredContactMethod preferredContactMethod;

    @Schema(description = "Number of contact points created")
    private int contactPointsCreated;

    @Schema(description = "Timestamp when the person was created")
    private Instant createdAt;

    /**
     * Creates a response from a Person entity.
     *
     * @param person             the created person
     * @param contactPointsCount number of contact points created
     * @return the response DTO
     */
    public static CreatePersonResponse from(Person person, int contactPointsCount) {
        return CreatePersonResponse.builder()
                .personId(person.getPersonId())
                .firstName(person.getFirstName())
                .lastName(person.getLastName())
                .preferredContactMethod(person.getPreferredContactMethod())
                .contactPointsCreated(contactPointsCount)
                .createdAt(person.getCreatedAt())
                .build();
    }
}
