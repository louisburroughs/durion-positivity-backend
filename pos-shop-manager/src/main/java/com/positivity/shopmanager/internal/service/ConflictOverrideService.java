package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.security.ShopPermissions;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideRequest;
import com.positivity.shopmanager.internal.service.dto.ConflictOverrideResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Records a manager's acceptance of SOFT scheduling conflicts (CAP-326, DECISION-SHOPMGMT-002 and
 * -007, spec D12/D18.3).
 *
 * <p>Gated by {@code shop:conflict:override} here as well as at the controller, so a direct caller
 * meets the same wall. The permission says <em>who</em> may override; the appointment's location
 * still decides <em>where</em> (ADR-0061, DECISION-012), which the implementation checks once it has
 * the appointment in hand.
 */
public interface ConflictOverrideService {

    /**
     * Writes one immutable {@code conflict_override} row per conflict named. Every conflict must be
     * recorded against {@code appointmentId} (400 otherwise), be SOFT (409 with the DECISION-002
     * envelope otherwise — HARD is never overridden) and not yet carry an override (409 otherwise).
     */
    @PreAuthorize("hasAuthority('" + ShopPermissions.CONFLICT_OVERRIDE + "')")
    @NonNull
    ConflictOverrideResponse execute(@NonNull UUID appointmentId, @NonNull ConflictOverrideRequest request);
}
