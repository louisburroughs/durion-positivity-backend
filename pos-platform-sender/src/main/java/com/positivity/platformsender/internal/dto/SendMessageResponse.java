package com.positivity.platformsender.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Acceptance of a send request (FI-2 §1): {@code 202} on first acceptance, {@code 200} with the
 * same body on an idempotent replay.
 *
 * @param providerMessageId the provider's id for the message; the only key outcomes correlate on
 * @param addressHash SHA-256 (lowercase hex) of the normalized address the message went to
 */
@Schema(description = "Accepted send: the provider message id outcomes will carry")
public record SendMessageResponse(
        @Schema(description = "Provider message id; correlates sender.outcomes.v1 events") @NonNull
        String providerMessageId,

        @Schema(description = "SHA-256 of the normalized address, lowercase hex") @Nullable
        String addressHash) {}
