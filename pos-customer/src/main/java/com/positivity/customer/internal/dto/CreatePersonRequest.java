package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import com.positivity.customer.internal.enums.PreferredContactMethod;

/**
 * Request DTO for creating an individual person record.
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/111">Backend
 *      Issue #111</a>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to create an individual person record")
public class CreatePersonRequest {

    @NotBlank(message = "firstName is required")
    @Schema(description = "First name of the person", example = "John", requiredMode = Schema.RequiredMode.REQUIRED)
    private String firstName;

    @NotBlank(message = "lastName is required")
    @Schema(description = "Last name of the person", example = "Doe", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lastName;

    @NotNull(message = "preferredContactMethod is required")
    @Schema(description = "Preferred method of contact", example = "EMAIL", requiredMode = Schema.RequiredMode.REQUIRED)
    private PreferredContactMethod preferredContactMethod;

    @Valid
    @Schema(description = "Email addresses for this person")
    private List<EmailInput> emails;

    @Valid
    @Schema(description = "Phone numbers for this person")
    private List<PhoneInput> phones;

    /**
     * Email input for person creation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Email address input")
    public static class EmailInput {
        @NotBlank(message = "email value is required")
        @Email(message = "Invalid email format")
        @Schema(description = "Email address", example = "john.doe@example.com")
        private String value;

        @Schema(description = "Whether this is the primary email", example = "true")
        private boolean isPrimary;
    }

    /**
     * Phone input for person creation.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "Phone number input")
    public static class PhoneInput {
        @NotBlank(message = "phone value is required")
        @Schema(description = "Phone number", example = "+1-555-123-4567")
        private String value;

        @Schema(description = "Phone type", example = "PHONE_MOBILE")
        private ContactPointType type;

        @Schema(description = "Whether this is the primary phone", example = "true")
        private boolean isPrimary;
    }
}
