package com.positivity.customer.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Preferred contact method for a person record.
 * Used to indicate how the person prefers to be contacted.
 */
@Schema(description = "Preferred contact method for a person")
public enum PreferredContactMethod {
    @Schema(description = "Contact via email")
    EMAIL,

    @Schema(description = "Contact via phone call")
    PHONE_CALL,

    @Schema(description = "Contact via SMS/text message")
    SMS,

    @Schema(description = "No preferred contact method specified")
    NONE
}
