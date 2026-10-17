package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtTenant;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtTenantRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.tenancy.replica.TenantProjectionEvent;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the {@code ext_tenant} locale replica (#2618). Each call is its own transaction and needs the fact's tenant
 * bound ({@code TenantContext.runAs}); the listener never calls it from inside another transaction.
 *
 * <p>A fact without a locale is "unknown", never a clear: it leaves the stored value alone. A fact older than the
 * stored row ({@link ReplicaVersionGuard}) is ignored; an equal version is applied, so replay repairs a bad row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantLocaleReplicaWriter {

    private final ExtTenantRepository tenants;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    /**
     * Stores the locale of {@code tenant.updated} and the other projection facts, and records the eventId in the same
     * transaction. A replayed eventId changes nothing.
     */
    @Transactional
    public void applyAndRecord(@NonNull TenantProjectionEvent fact) {
        if (processedEvents.existsById(fact.eventId())) {
            return;
        }
        upsert(fact);
        processedEvents.save(ProcessedEvent.builder()
                .eventId(fact.eventId())
                .owner(AccountingTenantProvisioner.OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    /** Stores the locale of a fact whose eventId is recorded elsewhere ({@code tenant.created}). */
    @Transactional
    public void apply(@NonNull TenantProjectionEvent fact) {
        upsert(fact);
    }

    private void upsert(TenantProjectionEvent fact) {
        if (fact.locale() == null || fact.locale().isBlank()) {
            return;
        }
        ExtTenant row = tenants.findById(fact.tenantId()).orElse(null);
        if (row != null && ReplicaVersionGuard.isStale(row.getAggregateVersion(), fact.aggregateVersion())) {
            log.debug("Ignoring stale tenant fact eventId={} for tenant {}", fact.eventId(), fact.tenantId());
            return;
        }
        if (row == null) {
            row = new ExtTenant();
            row.setExtTenantId(fact.tenantId());
        }
        row.setLocale(fact.locale());
        row.setAggregateVersion(fact.aggregateVersion());
        tenants.save(row);
    }
}
