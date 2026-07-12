package com.positivity.peoplecontact.internal.client.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Scope at which a role assignment applies")
public enum ScopeType {
    GLOBAL,
    LOCATION
}
