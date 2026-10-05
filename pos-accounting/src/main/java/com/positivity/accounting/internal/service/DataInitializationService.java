package com.positivity.accounting.internal.service;

/**
 * The policy rows a tenant starts with: the price-override thresholds and the refund policy.
 *
 * <p>A step of tenant provisioning ({@link AccountingTenantProvisioner}, #2526), not a startup
 * job: it runs with the tenant bound and inside the provisioning transaction.
 */
public interface DataInitializationService {

    /**
     * Gives the bound tenant three override thresholds and one refund policy, each only when the
     * tenant has none of its kind. Must run inside an active transaction opened under the tenant's
     * binding.
     *
     * @return how many rows were created (0 to 4)
     */
    int seedPolicyDefaults();
}
