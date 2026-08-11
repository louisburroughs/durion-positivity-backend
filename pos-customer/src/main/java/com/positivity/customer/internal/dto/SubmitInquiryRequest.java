package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.InquiryChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * An inbound service or fleet-quote request.
 *
 * <p>Every field is length-capped because this shape is also accepted on the unauthenticated
 * public endpoint, where the submitter is not a trusted caller.
 */
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to capture an inbound service or fleet-quote inquiry")
public class SubmitInquiryRequest {

    @Schema(description = "How the inquiry arrived", example = "WEB_FORM", requiredMode = REQUIRED)
    @NotNull
    private InquiryChannel channel;

    @Schema(
            description = "Whether this is a fleet or individual inquiry",
            example = "INDIVIDUAL",
            requiredMode = REQUIRED)
    @NotNull
    private AudienceType audienceType;

    @Schema(description = "Who is asking", example = "Jane Doe", requiredMode = REQUIRED)
    @NotBlank
    @Size(max = 200)
    private String contactName;

    @Schema(description = "Organization name, for a fleet inquiry", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 200)
    private String organizationName;

    @Schema(description = "Contact email", example = "jane@example.com", requiredMode = NOT_REQUIRED)
    @Nullable
    @Email
    @Size(max = 320)
    private String email;

    @Schema(description = "Contact phone", example = "+1-555-0100", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 40)
    private String phone;

    @Schema(
            description = "Vehicle the inquiry concerns",
            example = "2019 Ford Transit 250",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 300)
    private String vehicleOfInterest;

    @Schema(description = "Service the inquiry concerns", example = "Fleet tire rotation", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 300)
    private String serviceOfInterest;

    @Schema(description = "Free-text message", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 2000)
    private String message;

    @Schema(description = "Campaign that drove the inquiry", example = "SPRING-FLEET-2026", requiredMode = NOT_REQUIRED)
    @Nullable
    @Size(max = 100)
    private String campaignCode;
}
