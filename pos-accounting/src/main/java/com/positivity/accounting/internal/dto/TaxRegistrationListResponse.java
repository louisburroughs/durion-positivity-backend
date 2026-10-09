package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The tenant's tax registrations from accounting's copy (CAP:550 S32c): every one, or those in effect on {@code
 * asOf}.
 */
@Schema(description = "The tenant's tax registrations")
public record TaxRegistrationListResponse(
        @Schema(
                description = "The date asked for; absent when every registration is listed",
                requiredMode = NOT_REQUIRED)
        @Nullable
        LocalDate asOf,

        @Schema(description = "The registrations, in country, regime and start order", requiredMode = REQUIRED) @NonNull
        List<TaxRegistrationView> registrations) {}
