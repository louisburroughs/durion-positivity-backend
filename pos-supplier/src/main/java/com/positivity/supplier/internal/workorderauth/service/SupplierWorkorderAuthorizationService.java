package com.positivity.supplier.internal.workorderauth.service;

import com.positivity.supplier.internal.workorderauth.service.model.WorkorderAuthorizationView;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Read surface over fleet workorder authorization state at the vendor (ADR-0026 contract).
 *
 * <p>Authorization request/decision flows are event-driven per ADR-0049 §3
 * ({@code supplier.workorderauth.requested} / {@code supplier.workorderauth.granted} /
 * {@code supplier.workorderauth.denied}); workorder state itself stays in pos-workorder
 * (ADR-0049 §2). This interface exposes vendor-side authorization state for the module's own
 * frontend-facing controllers.
 *
 * <p>Placeholder-level for the CAP-317 foundation slice; implementation arrives with CAP-323
 * (Michelin S2S workorder authorization, durion#378).
 */
public interface SupplierWorkorderAuthorizationService {

    /**
     * Returns the vendor authorization state recorded for a workorder.
     *
     * @param workorderId pos-workorder identity the authorization concerns
     * @return the authorization view, or empty when no authorization was ever requested
     */
    @NonNull
    Optional<WorkorderAuthorizationView> findByWorkorderId(@NonNull UUID workorderId);
}
