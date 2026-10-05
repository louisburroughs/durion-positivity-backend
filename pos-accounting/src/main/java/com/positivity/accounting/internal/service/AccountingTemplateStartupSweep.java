package com.positivity.accounting.internal.service;

import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Applies the accounting template to every registry tenant once at startup (#2526).
 *
 * <p>It does two jobs the {@code tenant.created} listener cannot. It brings a tenant that has no
 * {@code tenant.created} fact under the template: the alpha default tenant is registered by
 * migration, so this is the path it is provisioned by, on a reset database and on one that ran the
 * old seeds alike. And it carries what the template has gained since a tenant was provisioned to
 * that tenant: the template changes only through Flyway, so "since the last start" is exactly
 * "since the last change".
 *
 * <p>Each tenant is provisioned in a transaction of its own, opened inside its binding
 * ({@link TenantIterator} binds, the provisioner's proxy opens). One tenant's failure is logged and
 * the next tenant still runs; nothing here stops the service from starting, an empty template
 * included (a database Flyway never ran on has none).
 *
 * <p>The sweep reaches only the tenants {@link TenantIterator} knows. Before a second tenant is
 * created on a cell, the module must use the remote registry ({@code
 * pos.tenancy.registry.mode=REMOTE}) or list its tenants, or template additions miss them.
 *
 * <p>Turn it off with {@code pos.accounting.tenant-template.startup-sweep.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.accounting.tenant-template.startup-sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AccountingTemplateStartupSweep implements ApplicationRunner {

    static final String ATTENTION_GAUGE = "accounting.tenant_template.tenants_needing_attention";

    private final TenantIterator tenantIterator;
    private final AccountingTemplateReader templateReader;
    private final AccountingTenantProvisioner provisioner;
    private final AtomicInteger tenantsNeedingAttention = new AtomicInteger();

    public AccountingTemplateStartupSweep(
            TenantIterator tenantIterator,
            AccountingTemplateReader templateReader,
            AccountingTenantProvisioner provisioner,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.tenantIterator = tenantIterator;
        this.templateReader = templateReader;
        this.provisioner = provisioner;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry != null) {
            Gauge.builder(ATTENTION_GAUGE, tenantsNeedingAttention, AtomicInteger::get)
                    .description("Tenants with an accounting template entry in conflict or withheld, as of the last"
                            + " startup sweep")
                    .register(registry);
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            sweep();
        } catch (EmptyAccountingTemplateException e) {
            log.error("Accounting template startup sweep skipped; no tenant was provisioned: {}", e.getMessage());
        } catch (Exception e) {
            log.error("Accounting template startup sweep failed; continuing startup", e);
        }
    }

    /**
     * Provisions every registry tenant.
     *
     * @return the number of tenants provisioned without error
     * @throws EmptyAccountingTemplateException when the platform tenant holds no template
     */
    int sweep() {
        AccountingTemplate snapshot = templateReader.snapshot();
        AtomicInteger attention = new AtomicInteger();
        int provisioned = tenantIterator.forEachActiveTenant(tenantId -> provisioner
                .provision(tenantId, null, snapshot)
                .filter(AccountingTemplateApplier.Result::needsAttention)
                .ifPresent(result -> attention.incrementAndGet()));
        tenantsNeedingAttention.set(attention.get());
        log.info(
                "Accounting template startup sweep: {} tenant(s) provisioned, {} needing attention",
                provisioned,
                attention.get());
        return provisioned;
    }
}
