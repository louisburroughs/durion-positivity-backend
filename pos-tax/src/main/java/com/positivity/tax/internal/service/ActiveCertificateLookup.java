package com.positivity.tax.internal.service;

import com.positivity.tax.common.enums.ExemptionReasonCode;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only port used by {@link ExemptionResolver} to resolve an active exemption
 * certificate on the calculate path (story T3). Keeping the lookup behind a small port
 * lets the calculator/resolver be unit-tested without a database.
 */
public interface ActiveCertificateLookup {

    /**
     * Resolve an active, effective, non-expired certificate backing an exemption claim.
     * <p>
     * When {@code certificateId} is supplied it is resolved by id and validated (active,
     * within its date window, and — when {@code customerId} is supplied — belonging to that
     * customer). Otherwise the most-recently-effective active certificate for
     * {@code customerId} (optionally scoped by {@code stateScope}/{@code requestedReason}) on
     * {@code date} is returned.
     *
     * @param customerId      the customer id from the request (may be {@code null})
     * @param certificateId   an explicit certificate id from the line/request (may be {@code null})
     * @param stateScope      destination state scope (may be {@code null})
     * @param requestedReason the claimed reason (may be {@code null})
     * @param date            the transaction date
     * @return the resolved active certificate, or empty when none is valid
     */
    @NonNull
    Optional<ActiveCertificate> findActive(
            @Nullable String customerId,
            @Nullable UUID certificateId,
            @Nullable String stateScope,
            @Nullable ExemptionReasonCode requestedReason,
            @NonNull LocalDate date);

    /**
     * A resolved active certificate.
     *
     * @param id         the certificate id
     * @param reasonCode the certificate's exemption reason
     */
    record ActiveCertificate(@NonNull UUID id, @NonNull ExemptionReasonCode reasonCode) {}
}
