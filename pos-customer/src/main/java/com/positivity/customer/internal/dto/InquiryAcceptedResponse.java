package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/**
 * The only thing the public capture endpoint returns.
 *
 * <p>Deliberately minimal: echoing the submission back, or revealing whether the contact
 * already matches a party, would turn an unauthenticated form into a customer-enumeration
 * oracle.
 */
@Schema(description = "Acknowledgement of a publicly submitted inquiry")
public record InquiryAcceptedResponse(
        @Schema(description = "Reference for the submitted inquiry", requiredMode = REQUIRED)
        UUID inquiryId,

        @Schema(description = "Always NEW", example = "NEW", requiredMode = REQUIRED)
        String status) {}
