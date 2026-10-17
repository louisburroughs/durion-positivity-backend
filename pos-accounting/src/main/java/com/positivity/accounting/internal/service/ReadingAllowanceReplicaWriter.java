package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.BillIntakeReadingAllowance;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.BillIntakeReadingAllowanceRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes the {@code bill_intake_reading_allowance} replica (#2619). Each call is its own transaction and needs the
 * fact's tenant bound ({@code TenantContext.runAs}); the listener never calls it from inside another transaction.
 *
 * <p>A fact older than the stored row ({@link ReplicaVersionGuard}) is ignored; an equal version is applied, so
 * replay repairs a bad row. The eventId is recorded in the same transaction, also for an ignored stale fact.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadingAllowanceReplicaWriter {

    private final BillIntakeReadingAllowanceRepository allowances;
    private final ProcessedEventRepository processedEvents;
    private final Clock clock;

    /** Stores the allowance and records the eventId in the same transaction. A replayed eventId changes nothing. */
    @Transactional
    public void applyAndRecord(@NonNull ReadingAllowanceEvent fact) {
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

    private void upsert(ReadingAllowanceEvent fact) {
        BillIntakeReadingAllowance row = allowances.findById(fact.tenantId()).orElse(null);
        if (row != null && ReplicaVersionGuard.isStale(row.getAggregateVersion(), fact.aggregateVersion())) {
            log.debug(
                    "Ignoring stale reading-allowance fact eventId={} for tenant {}", fact.eventId(), fact.tenantId());
            return;
        }
        if (row == null) {
            row = new BillIntakeReadingAllowance();
            row.setReadingAllowanceId(fact.tenantId());
        }
        row.setTierCode(fact.tierCode());
        row.setAmount(fact.amount());
        row.setCurrency(fact.currencyCode());
        row.setEffectiveAt(fact.effectiveAt());
        row.setAggregateVersion(fact.aggregateVersion());
        allowances.save(row);
    }
}
