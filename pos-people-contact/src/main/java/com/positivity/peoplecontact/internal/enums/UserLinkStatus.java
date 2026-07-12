package com.positivity.peoplecontact.internal.enums;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Status of a user-person link.
 */
@Schema(description = "Status of a user-person link")
public enum UserLinkStatus {
    @Schema(description = "Link is active - user is currently associated with person")
    ACTIVE,

    @Schema(description = "Link is inactive - historical association that is no longer active")
    INACTIVE
}
