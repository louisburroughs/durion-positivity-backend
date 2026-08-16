package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.entity.SupplierAccountEntity;
import com.positivity.supplier.internal.exception.SupplierConfigurationException;
import com.positivity.supplier.internal.service.SupplierProfileResolver;
import org.jspecify.annotations.NonNull;

/**
 * Resolving the {@code partnerId} the Michelin S2S spec requires on every operation (CAP-323 #1229).
 *
 * <h2>One rule, because three callers had drifted into two</h2>
 *
 * The spec requires {@code partnerId} on every call: it is how the vendor knows which service centre
 * is asking, and a fleet authorization from an unidentified caller is not something any vendor
 * grants. The request path treated a missing billing account number as the deployment defect it is;
 * the poller and the approver quietly substituted an empty string and carried on.
 *
 * <p>That difference was not visible at any call site. The poller would have polled a request the
 * vendor had rejected as unidentified until it timed out a day later, and the approver would have
 * spent its whole retry budget on a call that could never succeed — escalating a money-critical
 * approval for a reason that had nothing to do with the vendor. Both would have reported the
 * misconfiguration as a vendor problem.
 */
final class S2SPartnerId {

    private S2SPartnerId() {}

    /**
     * The service-provider identity for this vendor, from the profile's billing account.
     *
     * @throws SupplierConfigurationException when no billing account number is configured — a
     *     deployment defect, surfaced loudly rather than sent as an empty string
     */
    @NonNull
    static String resolve(@NonNull SupplierProfileResolver profileResolver, @NonNull SupplierRef supplierRef) {
        SupplierAccountEntity billing =
                profileResolver.resolvePartyContext(supplierRef, null).billing();
        String partnerId = billing.getAccountNumber();
        if (partnerId == null || partnerId.isBlank()) {
            throw new SupplierConfigurationException(
                    SupplierConfigurationException.CAPABILITY_NOT_CONFIGURED,
                    "No billing account number configured for " + supplierRef.value()
                            + "; the S2S partnerId has nowhere to come from");
        }
        return partnerId;
    }
}
