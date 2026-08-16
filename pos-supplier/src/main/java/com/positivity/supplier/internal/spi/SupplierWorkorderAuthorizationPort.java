package com.positivity.supplier.internal.spi;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierWorkorderAuthorization;
import com.positivity.supplier.internal.domain.model.WorkorderAuthorizationRequest;
import org.jspecify.annotations.NonNull;

/**
 * Capability port: fleet workorder authorization at the vendor
 * ({@code WORKORDER_AUTHORIZATION}; Michelin S2S — not an EDIWheel norm).
 *
 * <p>Governing ADRs: ADR-0049 §2/§3 — workorder state stays in pos-workorder; the vendor
 * decision surfaces as {@code supplier.workorderauth.granted}/{@code denied} facts.
 *
 * <h2>Two deliberate deviations from the sibling ports</h2>
 *
 * <p><strong>It takes a canonical request rather than a {@code PartyContext} and an id.</strong>
 * The original signature could not express what a fleet program actually needs — how the vehicle is
 * identified, which contract is claimed, what work is being asked for. Implementing it would have
 * meant inventing a vehicle identifier out of an account number, which compiles, passes a test, and
 * cannot work against a real vendor. The signature was widened to the information the operation
 * genuinely requires (CAP-323 #1229).
 *
 * <p><strong>It returns the decision, not a {@code SupplierExchange}.</strong> The wrapper carries
 * an exchange audit id and correlation id that the caller of this port has no use for: an
 * authorization is decided over minutes or hours and possibly across several exchanges, so there is
 * no single exchange to point at. The audit trail is written by the base client regardless and is
 * queried by exchange, not by return value.
 *
 * <h2>A note on the SPI layer as a whole</h2>
 *
 * As of CAP-323 this is the only capability port with an implementation. The delivered capabilities
 * — stock inquiry, orders, price catalogue, stock report, invoices — all route through their own
 * {@code *Runner} and never reference their port. The ports were declared by the CAP-317 foundation
 * and the capabilities that followed did not adopt them. That is worth knowing before treating this
 * package as the module's contract surface: today it mostly is not.
 */
public interface SupplierWorkorderAuthorizationPort {

    /**
     * Asks the vendor's fleet program to authorize the requested work.
     *
     * @param supplierRef the fleet program to ask
     * @param request     what is being asked for, including how the vehicle is identified
     * @return the vendor's decision, which may be {@code PENDING} when the vendor accepted the
     *     request and will decide later
     */
    @NonNull
    SupplierWorkorderAuthorization requestAuthorization(
            @NonNull SupplierRef supplierRef, @NonNull WorkorderAuthorizationRequest request);
}
