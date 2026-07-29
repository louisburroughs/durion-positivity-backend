package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A captured inbound inquiry")
public record InquiryResponse(
        @Schema(description = "Inquiry identifier", requiredMode = REQUIRED)
        UUID inquiryId,

        @Schema(
                description = "How it arrived",
                allowableValues = {"WEB_FORM", "PHONE", "WALK_IN", "EMAIL", "REFERRAL", "CAMPAIGN"},
                requiredMode = REQUIRED)
        String channel,

        @Schema(
                description = "Fleet or individual",
                allowableValues = {"COMMERCIAL", "INDIVIDUAL"},
                requiredMode = REQUIRED)
        String audienceType,

        @Schema(description = "Who is asking", requiredMode = REQUIRED)
        String contactName,

        @Schema(description = "Organization name", requiredMode = NOT_REQUIRED)
        String organizationName,

        @Schema(description = "Contact email", requiredMode = NOT_REQUIRED)
        String email,

        @Schema(description = "Contact phone", requiredMode = NOT_REQUIRED)
        String phone,

        @Schema(description = "Vehicle of interest", requiredMode = NOT_REQUIRED)
        String vehicleOfInterest,

        @Schema(description = "Service of interest", requiredMode = NOT_REQUIRED)
        String serviceOfInterest,

        @Schema(description = "Free-text message", requiredMode = NOT_REQUIRED)
        String message,

        @Schema(description = "Attributed campaign", requiredMode = NOT_REQUIRED)
        String campaignCode,

        @Schema(
                description = "Inquiry state",
                allowableValues = {"NEW", "CONTACTED", "CONVERTED", "CLOSED"},
                requiredMode = REQUIRED)
        String status,

        @Schema(description = "Party it converted into", requiredMode = NOT_REQUIRED)
        UUID partyId,

        @Schema(description = "Owning CSR", requiredMode = NOT_REQUIRED)
        String assignedTo,

        @Schema(description = "How it was resolved", requiredMode = NOT_REQUIRED)
        String resolutionNote,

        @Schema(description = "Capture timestamp", requiredMode = REQUIRED)
        Instant createdAt) {}
