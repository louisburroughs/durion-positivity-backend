package com.positivity.inboundmail.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Rotation request. {@code requestId} is pos-accounting's own id for the rotation: a retry of a
 * rotation whose answer was lost sends the same one and never rotates twice. No reason travels to
 * the edge; it lives in pos-accounting's audit.
 *
 * @param requestId idempotency key, a UUID chosen by the caller
 */
@Schema(description = "Rotation request keyed by the caller's own request id")
public record RotateAddressRequest(
        @Schema(description = "Caller's idempotency key; a replay returns the address this request issued") @NotNull
        UUID requestId) {}
