package com.positivity.customer.internal.service;

import com.positivity.tenancy.TenantIterator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps exactly one CASH house account per active tenant (CAP:550 S7, #2505; accounting workspace
 * spec §4.4 item 2).
 *
 * <p>Runs once at startup and then on a fixed-delay sweep
 * ({@code pos.customer.house-account.sweep-interval-ms}, default one hour). pos-customer does not
 * consume {@code tenant.events.v1} yet, so the sweep is what gives a tenant added while the service
 * runs its account; it is replaced when provisioning moves to that topic.
 *
 * <p>Each tenant is visited through {@link TenantIterator#forEachActiveTenant} — tenant bound, one
 * transaction per tenant, never the platform tenant (ADR-0062). The sweep is idempotent: a
 * provisioned tenant is a no-op, and when two instances race, the loser's insert is refused by the
 * partial unique index and counted as already provisioned, with no second fact. A tenant whose
 * transaction fails is logged at WARN and retried on the next sweep; the other tenants proceed.
 *
 * <p>{@code pos.customer.house-account.enabled=false} removes the bean, which is how a test
 * context that must not see a house account switches it off.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.customer.house-account",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class HouseAccountProvisioner implements ApplicationRunner {

    /** Counter of per-tenant provisioning attempts, tagged {@code outcome=created|existing|failed}. */
    static final String METRIC = "customer.house_account.provisioned";

    /** What one tenant's provisioning attempt came to. */
    enum Outcome {
        CREATED,
        EXISTING,
        FAILED
    }

    private final TenantIterator tenantIterator;
    private final HouseAccountProvisioningService provisioningService;

    /** Absent in contexts without a metrics registry; provisioning must not depend on it. */
    private final @Nullable MeterRegistry meterRegistry;

    public HouseAccountProvisioner(
            TenantIterator tenantIterator,
            HouseAccountProvisioningService provisioningService,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.tenantIterator = tenantIterator;
        this.provisioningService = provisioningService;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /** Startup pass. Logs and swallows any failure: provisioning never blocks startup. */
    @Override
    public void run(ApplicationArguments args) {
        try {
            sweep();
        } catch (RuntimeException e) {
            log.warn("House account provisioning failed at startup; the next sweep retries", e);
        }
    }

    /**
     * Provision every active tenant that has no CASH house account yet. The first scheduled pass
     * waits one full interval, because {@link #run} already covered startup.
     */
    @Scheduled(
            fixedDelayString = "${pos.customer.house-account.sweep-interval-ms:3600000}",
            initialDelayString = "${pos.customer.house-account.sweep-interval-ms:3600000}")
    public void sweep() {
        tenantIterator.forEachActiveTenant(this::provisionTenant);
    }

    /**
     * One tenant's attempt, run with that tenant bound. Never throws: a failure is this tenant's
     * alone and is retried on the next sweep.
     */
    @NonNull
    Outcome provisionTenant(@NonNull UUID tenantId) {
        Outcome outcome = attempt(tenantId);
        count(outcome);
        return outcome;
    }

    private Outcome attempt(UUID tenantId) {
        try {
            Optional<UUID> created = provisioningService.createCashAccountIfMissing();
            if (created.isEmpty()) {
                return Outcome.EXISTING;
            }
            log.info("Provisioned the CASH house account: tenantId={}, partyId={}", tenantId, created.get());
            return Outcome.CREATED;
        } catch (DataIntegrityViolationException e) {
            return afterRefusedInsert(tenantId, e);
        } catch (RuntimeException e) {
            log.warn("House account provisioning failed for tenant {}; the next sweep retries", tenantId, e);
            return Outcome.FAILED;
        }
    }

    /**
     * The insert was refused. If the tenant now has its account, another instance won the race on
     * the unique index and theirs is ours; anything else is a real failure.
     */
    private Outcome afterRefusedInsert(UUID tenantId, DataIntegrityViolationException refused) {
        try {
            if (provisioningService.cashAccountExists()) {
                log.debug("CASH house account for tenant {} was provisioned concurrently", tenantId);
                return Outcome.EXISTING;
            }
        } catch (RuntimeException e) {
            refused.addSuppressed(e);
        }
        log.warn("House account provisioning failed for tenant {}; the next sweep retries", tenantId, refused);
        return Outcome.FAILED;
    }

    private void count(Outcome outcome) {
        if (meterRegistry != null) {
            Counter.builder(METRIC)
                    .description("CASH house account provisioning attempts per tenant, by outcome")
                    .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                    .register(meterRegistry)
                    .increment();
        }
    }
}
