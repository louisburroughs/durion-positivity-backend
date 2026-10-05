package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.enums.TemplateEntryOutcome;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Provisions one tenant's accounting defaults in one transaction (#2526; ADR-0062 §7): the
 * template entries of every source that applies to it, then the policy defaults, then, when a
 * {@code tenant.created} fact asked for it, the fact's eventId.
 *
 * <p>Every path into provisioning ends here: the {@code tenant.created} listener, the startup
 * sweep, and a tenant turning an add-on on. All of them are idempotent on the tenant, because the
 * applier is: a second call from any path writes nothing.
 *
 * <p><strong>Calling it.</strong> The caller binds the tenant first and calls through the Spring
 * proxy, so the transaction opens inside the binding and its connection is the tenant's
 * ({@code TenantContext.runAs(tenantId, () -> provisioner.provision(...))}). The template snapshot
 * is read before that, under the platform binding. The method refuses to run for a tenant other
 * than the bound one, and for the platform tenant, which holds the template and is never
 * provisioned.
 */
@Slf4j
@Service
public class AccountingTenantProvisioner {

    /** {@code processed_events.owner} of the {@code tenant.created} facts recorded here. */
    public static final String OWNER = "tenant";

    /** How a provisioning run was started; the {@code path} tag of the entries counter. */
    public enum Path {
        /** A {@code tenant.created} fact. */
        EVENT("event"),
        /** The startup sweep. */
        STARTUP("startup"),
        /** A tenant turned an opt-in source on. */
        ADD_ON("add-on");

        private final String tag;

        Path(String tag) {
            this.tag = tag;
        }

        /** The value of the {@code path} metric tag. */
        public String tag() {
            return tag;
        }
    }

    static final String ENTRIES_COUNTER = "accounting.tenant_template.entries";

    private final AccountingTemplateApplier applier;
    private final List<AccountingTemplateSource> sources;
    private final DataInitializationService policyDefaults;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;
    private final @Nullable MeterRegistry meterRegistry;

    public AccountingTenantProvisioner(
            AccountingTemplateApplier applier,
            List<AccountingTemplateSource> sources,
            DataInitializationService policyDefaults,
            ProcessedEventRepository processedEvents,
            Clock clock,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.applier = applier;
        this.sources = List.copyOf(sources);
        this.policyDefaults = policyDefaults;
        this.processedEvents = processedEvents;
        this.clock = clock;
        this.meterRegistry = meterRegistry.getIfAvailable();
    }

    /**
     * Provisions {@code tenantId} from a {@code tenant.created} fact, or from the startup sweep
     * when {@code eventId} is {@code null}.
     *
     * @param eventId the fact's eventId, recorded in this transaction; {@code null} at startup
     * @param snapshot the whole template, read under the platform binding before the tenant was bound
     * @return what the applier did; empty when the eventId was already recorded and nothing ran
     */
    @Transactional
    public @NonNull Optional<AccountingTemplateApplier.Result> provision(
            @NonNull UUID tenantId, @Nullable String eventId, @NonNull AccountingTemplate snapshot) {
        requireBound(tenantId);
        // Re-checked inside the transaction: the listener's own check ran before it, and a
        // redelivery to a second consumer can pass that check while this run is still in flight.
        if (eventId != null && processedEvents.existsById(eventId)) {
            log.debug("tenant.created eventId={} already processed for tenant {}", eventId, tenantId);
            return Optional.empty();
        }
        AccountingTemplateApplier.Result result = run(tenantId, snapshot, eventId == null ? Path.STARTUP : Path.EVENT);
        if (eventId != null) {
            recordEvent(eventId);
        }
        return Optional.of(result);
    }

    /**
     * Brings {@code tenantId} up to the template after a source started to apply to it.
     *
     * @param snapshot the whole template, as an earlier read left it
     */
    @Transactional
    public AccountingTemplateApplier.@NonNull Result reconcile(
            @NonNull UUID tenantId, @NonNull AccountingTemplate snapshot) {
        requireBound(tenantId);
        return run(tenantId, snapshot, Path.ADD_ON);
    }

    /** Records a {@code tenant.created} eventId that was looked at and deliberately not provisioned. */
    @Transactional
    public void recordSkipped(@NonNull String eventId) {
        if (!processedEvents.existsById(eventId)) {
            recordEvent(eventId);
        }
    }

    /** The entries {@code tenantId} is to hold: those of the sources that apply to it. Needs the tenant bound. */
    public @NonNull AccountingTemplate templateFor(@NonNull UUID tenantId, @NonNull AccountingTemplate snapshot) {
        List<AccountingTemplateSource> applying =
                sources.stream().filter(source -> source.appliesTo(tenantId)).toList();
        return snapshot.only(entry -> applying.stream().anyMatch(source -> source.owns(entry)));
    }

    private AccountingTemplateApplier.Result run(UUID tenantId, AccountingTemplate snapshot, Path path) {
        if (snapshot.isEmpty()) {
            throw new EmptyAccountingTemplateException();
        }
        AccountingTemplateApplier.Result result = applier.apply(tenantId, templateFor(tenantId, snapshot));
        int policyRows = policyDefaults.seedPolicyDefaults();
        count(result.changes(), path);
        if (result.changed() || policyRows > 0) {
            log.info(
                    "Accounting template applied: tenant={} path={} changes={} totals={} policyRows={}",
                    tenantId,
                    path.tag(),
                    result.changes(),
                    result.totals(),
                    policyRows);
        } else {
            log.info(
                    "Accounting template already applied: tenant={} path={} totals={}",
                    tenantId,
                    path.tag(),
                    result.totals());
        }
        return result;
    }

    private void recordEvent(String eventId) {
        processedEvents.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void count(Map<TemplateEntryOutcome, Integer> changes, Path path) {
        if (meterRegistry == null) {
            return;
        }
        changes.forEach((outcome, entries) -> Counter.builder(ENTRIES_COUNTER)
                .description("Accounting template entries applied to tenants, by outcome and path")
                .tag("outcome", outcome.name())
                .tag("path", path.tag())
                .register(meterRegistry)
                .increment(entries));
    }

    private static void requireBound(UUID tenantId) {
        if (PlatformTenant.isPlatform(tenantId)) {
            throw new IllegalArgumentException(
                    "The platform tenant holds the accounting template and is never provisioned");
        }
        UUID bound = TenantContext.current().orElse(null);
        if (!tenantId.equals(bound)) {
            throw new IllegalStateException("Accounting provisioning for tenant " + tenantId
                    + " must run under that tenant's binding, not " + bound);
        }
    }
}
