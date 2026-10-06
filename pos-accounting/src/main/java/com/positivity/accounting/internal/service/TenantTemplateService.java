package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.EnableTemplateAddOnRequest;
import com.positivity.accounting.internal.dto.TenantTemplateStatusResponse;
import org.jspecify.annotations.NonNull;

/** The caller's tenant and the accounting template (#2526): where it stands, and its add-on choices. */
public interface TenantTemplateService {

    /** Where the bound tenant stands against the template. Read-only. */
    @NonNull
    TenantTemplateStatusResponse status();

    /**
     * Records the bound tenant's choice of the retread-plant add-on, audits it with the caller, and
     * brings the tenant up to the template. A tenant that already has the add-on is left as it is,
     * which is also what makes a replayed request harmless. There is no switching off.
     *
     * @return the tenant's status after the call
     */
    @NonNull
    TenantTemplateStatusResponse enableRetreadPlantAddOn(@NonNull EnableTemplateAddOnRequest request);
}
