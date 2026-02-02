package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Type of contact point for a person.
 */
@Schema(description = "Type of contact point")
public enum ContactPointType {
    @Schema(description = "Email address")
    EMAIL,

    @Schema(description = "Mobile phone number")
    PHONE_MOBILE,

    @Schema(description = "Home phone number")
    PHONE_HOME,

    @Schema(description = "Work phone number")
    PHONE_WORK,

    @Schema(description = "Fax number")
    FAX
}
