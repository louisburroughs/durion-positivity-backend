package com.positivity.tenant.internal.service;

import com.positivity.tenant.internal.entity.ProcessedEvent;
import com.positivity.tenant.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one {@code tenant.provisioned} event: the status change and the {@code processed_events}
 * ledger row commit together, so redelivery is harmless (ADR-0044 §4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantProvisioningHandler {

    /** Producing domain of {@code tenant.provisioned}. */
    static final String OWNER = "security";

    private final TenantService tenantService;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    @Transactional
    public void apply(@NonNull String eventId, @NonNull UUID tenantId) {
        if (processedEventRepository.existsById(eventId)) {
            log.debug("tenant.provisioned eventId={} already applied", eventId);
            return;
        }
        tenantService.markProvisioned(tenantId);
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }
}
