package com.positivity.accounting.internal.service;

/**
 * The policy rows a tenant starts with: the price-override thresholds, the refund policy and the
 * accounting time zone.
 *
 * <p>A step of tenant provisioning ({@link AccountingTenantProvisioner}, #2526), not a startup
 * job: it runs with the tenant bound and inside the provisioning transaction.
 */
public interface DataInitializationService {

    /**
     * Gives the bound tenant three override thresholds, one refund policy and the {@code UTC}
     * accounting time zone (#2558), each only when the tenant has none of its kind. Must run
     * inside an active transaction opened under the tenant's
     * binding.
     *
     * @return how many rows were created (0 to 5)
     */
    int seedPolicyDefaults();
}
