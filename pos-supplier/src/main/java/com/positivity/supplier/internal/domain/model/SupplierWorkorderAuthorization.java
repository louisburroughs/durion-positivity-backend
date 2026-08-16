package com.positivity.supplier.internal.domain.model;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A fleet program's answer about a workorder (CAP-323 #1229).
 *
 * <h2>PENDING is an answer too</h2>
 *
 * The vendor may accept the request and decide later (HTTP 202). That is not a failure and not a
 * grant; it is a promise to answer at {@link #pollLocation}. Collapsing it into either would be
 * wrong in a way nobody notices until a shop either starts unauthorized work or turns away a fleet
 * customer it was going to be paid for.
 *
 * @param vendorAuthorizationId the vendor's identifier, where it gave one. Required to approve
 *                              completion later, so its absence on a grant is a defect worth seeing
 * @param workorderId           the pos-workorder identity the decision concerns
 * @param status                the decision, or {@code PENDING} while the vendor is still deciding
 * @param vendorReason          the vendor's stated reason, for a denial. Never invented
 * @param vendorReasonCode      the vendor's own code, untranslated: it is what a shop quotes back
 * @param contractReference     the contract the work was authorized under, as stated
 * @param authorizedAmount      the ceiling the vendor stated, when it stated one. Absent is not zero
 * @param currency              ISO 4217 for {@code authorizedAmount}; absent together with it
 * @param pollLocation          where an asynchronously accepted request said its answer will appear
 */
public record SupplierWorkorderAuthorization(
        @Nullable String vendorAuthorizationId,
        @NonNull UUID workorderId,
        @NonNull Status status,
        @Nullable String vendorReason,
        @Nullable String vendorReasonCode,
        @Nullable String contractReference,
        @Nullable BigDecimal authorizedAmount,
        @Nullable String currency,
        @Nullable String pollLocation) {

    /**
     * The vendor's position.
     *
     * <p>Note there is no value here for "we could not ask". That is deliberately not a vendor
     * answer and lives on the stored row as {@code MANUAL_REVIEW}, so no code path can turn an
     * unreachable vendor into a refusal.
     */
    public enum Status {
        PENDING,
        GRANTED,
        DENIED,
        NOT_FOUND
    }

    public SupplierWorkorderAuthorization {
        Objects.requireNonNull(workorderId, "workorderId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (authorizedAmount != null && currency == null) {
            throw new IllegalArgumentException("currency is required when authorizedAmount is present");
        }
    }

    /**
     * A vendor that accepted the request and will answer later.
     *
     * @param location where the answer will appear, from the 202's {@code Location} header
     */
    @NonNull
    public static SupplierWorkorderAuthorization pending(@NonNull UUID workorderId, @Nullable String location) {
        return new SupplierWorkorderAuthorization(
                null, workorderId, Status.PENDING, null, null, null, null, null, location);
    }

    /** Whether the vendor has finished deciding, so polling should stop. */
    public boolean isTerminal() {
        return status != Status.PENDING;
    }
}
