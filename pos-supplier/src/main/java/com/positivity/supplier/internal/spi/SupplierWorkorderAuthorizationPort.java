package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.SupplierExchange;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: fleet workorder authorization at the vendor
 * ({@code WORKORDER_AUTHORIZATION}; Michelin S2S — not an EDIWheel norm).
 *
 * <p>Governing ADRs: ADR-0049 §2/§3 — workorder state stays in pos-workorder; the vendor
 * decision surfaces as {@code supplier.workorderauth.granted}/{@code denied} results.
 * CAP-323 extends this port with completion approval and vehicle/contract/policy lookups
 * (architecture doc §6.1).
 */
public interface SupplierWorkorderAuthorizationPort {

    /** Requests authorization for the given workorder from the vendor's fleet program. */
    @NonNull
    SupplierExchange<SupplierWorkorderAuthorization> requestAuthorization(
            @NonNull SupplierRef supplierRef, @NonNull PartyContext partyContext, @NonNull UUID workorderId);
}
