package com.positivity.supplier.internal.workorderauth.service.model;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A fleet authorization as this module holds it (CAP-323 #1229).
 *
 * <p>Unlike the cross-module {@code WorkorderAuthorizationView}, this one does show
 * {@code MANUAL_REVIEW} and the reason for it. This is the operator-facing surface: the whole point
 * of the state is that somebody can see it and act, and hiding it here would leave the queue of
 * unpaid, unapproved work with no way to look at it.
 */
@Schema(description = "A fleet workorder authorization and its vendor-side sign-off state")
public record FleetAuthorizationResponse(
        @Schema(description = "Workorder the authorization concerns") @NonNull
        UUID workorderId,

        @Schema(description = "Vendor profile alias") @NonNull
        String supplierRef,

        @Schema(
                description =
                        "GRANTED, DENIED, NOT_FOUND, PENDING while the vendor decides, or MANUAL_REVIEW when no decision could be obtained")
        @NonNull
        String status,

        @Schema(description = "The vendor's own authorization id, needed to approve completion") @Nullable
        String vendorAuthorizationId,

        @Schema(description = "Contract the work was authorized under, as stated") @Nullable
        String contractReference,

        @Schema(description = "Ceiling the vendor stated, when it stated one") @Nullable
        BigDecimal authorizedAmount,

        @Schema(description = "ISO 4217 code for authorizedAmount") @Nullable
        String currency,

        @Schema(description = "The vendor's refusal code, as stated") @Nullable
        String reasonCode,

        @Schema(description = "The vendor's refusal text, as stated") @Nullable
        String reasonText,

        @Schema(description = "Why this authorization needs a human; never a vendor decision") @Nullable
        String reviewReason,

        @Schema(description = "NOT_REQUESTED, PENDING, APPROVED or MANUAL_REVIEW") @NonNull
        String approvalStatus,

        @Schema(description = "When the authorization was requested") @NonNull
        Instant requestedAt,

        @Schema(description = "When the vendor decided, if it has") @Nullable
        Instant decidedAt) {}
