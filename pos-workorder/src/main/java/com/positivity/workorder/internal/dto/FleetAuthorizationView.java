package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.WorkorderFleetAuthorization;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A fleet payment authorization as this domain holds it (#1346).
 *
 * <p>{@code MANUAL_REVIEW} is shown as itself, not folded into {@code PENDING}, because this is the
 * operator-facing surface: an advisor needs to see that nobody could get an answer, which is a
 * different problem from the fleet still deciding and points at a different fix.
 */
@Schema(description = "A workorder's fleet payment authorization")
public record FleetAuthorizationView(
        @NonNull UUID workorderId,
        @NonNull String supplierRef,
        @NonNull String status,
        @Nullable String vendorReasonCode,
        @Nullable String vendorReasonText,
        @Nullable String reviewReason,
        @Nullable String contractReference,
        @Nullable BigDecimal authorizedAmount,
        @Nullable String currency,
        @Nullable String resolution,
        @Nullable String resolutionReason,
        @Nullable Instant requestedAt,
        @Nullable Instant decidedAt) {

    @NonNull
    public static FleetAuthorizationView from(@NonNull WorkorderFleetAuthorization authorization) {
        return new FleetAuthorizationView(
                authorization.getWorkorderId(),
                authorization.getSupplierRef(),
                authorization.getStatus().name(),
                authorization.getVendorReasonCode(),
                authorization.getVendorReasonText(),
                authorization.getReviewReason(),
                authorization.getContractReference(),
                authorization.getAuthorizedAmount(),
                authorization.getCurrency(),
                authorization.getResolution() == null
                        ? null
                        : authorization.getResolution().name(),
                authorization.getResolutionReason(),
                authorization.getRequestedAt(),
                authorization.getDecidedAt());
    }
}
