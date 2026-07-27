package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Snooze (or unsnooze) a replenishment policy (odoo-parity F3, issue #1041 — Odoo
 * orderpoint snooze). A non-null {@code snoozedUntil} must be in the future (422
 * otherwise); {@code null} clears an existing snooze — there is no separate unsnooze
 * endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Request to snooze a replenishment policy until a future instant, or clear an existing snooze"
                + " by sending a null snoozedUntil")
public class SnoozeReplenishmentPolicyRequest {

    @Schema(
            description = "Future instant until which the policy is excluded from replenishment evaluation;"
                    + " null clears an existing snooze",
            example = "2026-02-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant snoozedUntil;
}
