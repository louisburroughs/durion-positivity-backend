package com.positivity.supplier.internal.vendor.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * One reveal of a vendor's tax-registration number, as the reveal audit read returns it (#2621). It never
 * carries the number or {@code last4}.
 *
 * @param revealId the audit row's identity
 * @param registrationId the registration revealed
 * @param scheme its scheme at the time
 * @param revealedBy the principal, from the security context (ADR-0018)
 * @param revealedByRoles the roles the principal held at the time
 * @param reason the reason given
 * @param correlationId the request's correlation id
 * @param revealedAt when
 * @param outcome {@code REVEALED} or {@code UNREADABLE}
 */
@Schema(description = "One reveal of a vendor tax-registration number. Never carries the number or last4.")
public record TaxIdRevealRecordView(
        @Schema(description = "Audit row identity (UUIDv7).", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d") @NonNull
        UUID revealId,

        @Schema(description = "Registration revealed.", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c") @NonNull
        UUID registrationId,

        @Schema(description = "Its scheme at the time.", example = "GST_HST") @NonNull
        String scheme,

        @Schema(description = "Who revealed it, from the security context.", example = "controller.b") @NonNull
        String revealedBy,

        @Schema(description = "Roles the person held at the time.", example = "[\"CONTROLLER\"]") @NonNull
        List<String> revealedByRoles,

        @Schema(description = "Reason given.", example = "Verifying W-9 received 2026-10-08") @NonNull
        String reason,

        @Schema(description = "Correlation id of the request.", example = "c0ffee00-0000-7000-8000-000000000001")
        @Nullable
        String correlationId,

        @Schema(description = "When.") @NonNull Instant revealedAt,

        @Schema(description = "REVEALED or UNREADABLE.", example = "REVEALED") @NonNull
        TaxIdRevealOutcome outcome) {}
