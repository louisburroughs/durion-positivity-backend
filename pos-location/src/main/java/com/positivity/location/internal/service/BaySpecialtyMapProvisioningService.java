package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds a newly created tenant's bay specialty map from the platform template on
 * {@code tenant.created} (DECISION-LOCATION-025, pattern: pos-security-service's
 * {@code TenantEventsListener}/{@code TenantProvisioningService}).
 *
 * <p>Runs entirely with the target tenant bound ({@code TenantEventsListener} calls this inside
 * {@code TenantContext.runAs(tenantId, ...)}), so {@code operationRepository} reads and writes the
 * new tenant's own rows throughout.
 *
 * <p><strong>Idempotent</strong>: a tenant that already has any {@code bay_specialty_operation}
 * rows — a replayed {@code tenant.created}, or a tenant provisioned before this listener existed —
 * is left alone: no copy, no version bump, no fact. The {@code processed_events} eventId guard
 * additionally short-circuits a plain redelivery before either check runs. Copy, the
 * {@code processed_events} mark, and the map-changed publish all happen in one transaction, so a
 * failure between them can never leave the tenant half-provisioned.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BaySpecialtyMapProvisioningService {

    static final String OWNER = "tenant";

    private final BaySpecialtyOperationRepository operationRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final BaySpecialtyMapPublisher publisher;
    private final Clock clock;

    @Transactional
    public void provisionIfNeeded(
            @NonNull UUID tenantId, @NonNull String eventId, @NonNull List<PlatformRow> platformRows) {
        if (processedEventRepository.existsById(eventId)) {
            // Redelivered after a previous attempt already committed; nothing left to do.
            return;
        }
        boolean alreadyProvisioned = !operationRepository.findAll().isEmpty();
        if (!alreadyProvisioned) {
            for (PlatformRow platformRow : platformRows) {
                operationRepository.save(BaySpecialtyOperationEntity.builder()
                        .bayType(platformRow.bayType())
                        .operationCode(platformRow.operationCode())
                        .build());
            }
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
        if (alreadyProvisioned) {
            log.debug("Tenant {} already has a bay specialty map; tenant.created {} is a no-op", tenantId, eventId);
        } else {
            log.info(
                    "Provisioned the bay specialty map for tenant {} from the platform template ({} rows)",
                    tenantId,
                    platformRows.size());
            publisher.publishChanged(tenantId);
        }
    }

    /** One platform-tenant specialty-map row, read before the target tenant is bound. */
    public record PlatformRow(
            @NonNull String bayType, @NonNull String operationCode) {}
}
