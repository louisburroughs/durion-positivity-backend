package com.positivity.location.internal.service;

import com.positivity.location.internal.entity.BaySpecialtyOperationEntity;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.BaySpecialtyOperationRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
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
 * Seeds a tenant's bay specialty map from the platform template (DECISION-LOCATION-025, pattern:
 * pos-security-service's {@code TenantEventsListener}/{@code TenantProvisioningService}), on two
 * paths that share this one copy routine:
 *
 * <ul>
 *   <li>{@link #provisionIfNeeded} — {@code TenantEventsListener} on {@code tenant.created}, guarded
 *       by the event's {@code processed_events} eventId.
 *   <li>{@link #provisionIfMissing} — {@code BaySpecialtyMapStartupPublisher}'s boot-time sweep, for
 *       a tenant created before that listener existed and so never provisioned; there is no
 *       {@code tenant.created} eventId to guard here, so emptiness is the only signal.
 * </ul>
 *
 * <p>Both run entirely with the target tenant bound ({@code TenantContext.runAs(tenantId, ...)},
 * from the caller in each case), so {@code operationRepository} reads and writes the target
 * tenant's own rows throughout. {@link #readPlatformTemplate()} is the one place either caller reads
 * the platform tenant's rows, switching to {@code PlatformTenant.ID} to do it.
 *
 * <p><strong>Idempotent</strong>: a tenant that already has any {@code bay_specialty_operation} rows
 * — a replayed {@code tenant.created}, a tenant the sweep has already backfilled, or one provisioned
 * before either path existed — is left alone by both methods: no copy, no version bump, no fact.
 * Copy and the map-changed publish always happen in the same transaction as any bookkeeping (the
 * {@code processed_events} mark, on the listener path), so a failure between them can never leave a
 * tenant half-provisioned.
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

    /**
     * The platform tenant's specialty map, read under its own binding — the template both
     * provisioning paths copy from. Never returns null; an empty list is a real (if pathological)
     * template, not a read failure — {@code R__seed_location_2_bay_specialty.sql} is the one thing
     * that is supposed to keep it non-empty.
     */
    public @NonNull List<PlatformRow> readPlatformTemplate() {
        return TenantContext.callAs(
                PlatformTenant.ID,
                () -> operationRepository.findAll().stream()
                        .map(row -> new PlatformRow(row.getBayType(), row.getOperationCode()))
                        .toList());
    }

    /** On {@code tenant.created}: copy-if-empty, guarded by the event's own {@code processed_events} row. */
    @Transactional
    public void provisionIfNeeded(
            @NonNull UUID tenantId, @NonNull String eventId, @NonNull List<PlatformRow> platformRows) {
        if (processedEventRepository.existsById(eventId)) {
            // Redelivered after a previous attempt already committed; nothing left to do.
            return;
        }
        boolean copied = copyIfEmpty(tenantId, platformRows);
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
        if (!copied) {
            log.debug("Tenant {} already has a bay specialty map; tenant.created {} is a no-op", tenantId, eventId);
        }
    }

    /**
     * Startup-sweep backfill for a tenant that never received (or never processed) its own
     * {@code tenant.created}: copies the platform template if the tenant's map is still empty, and
     * publishes the change. No {@code processed_events} row is written or consulted — there is no
     * event id here to dedupe on, so emptiness is the guard on its own, exactly as it is inside
     * {@link #provisionIfNeeded}.
     *
     * @return true if the tenant was empty and got copied and published; false if it already had
     *     rows and nothing happened — the caller republishes the current map itself in that case
     */
    @Transactional
    public boolean provisionIfMissing(@NonNull UUID tenantId, @NonNull List<PlatformRow> platformRows) {
        return copyIfEmpty(tenantId, platformRows);
    }

    /**
     * The shared copy routine: only when the target tenant's map is empty, copy every platform row
     * into it and publish the change. Reads and writes run under whatever tenant is already bound —
     * both callers bind the target tenant before reaching here.
     */
    private boolean copyIfEmpty(UUID tenantId, List<PlatformRow> platformRows) {
        if (!operationRepository.findAll().isEmpty()) {
            return false;
        }
        for (PlatformRow platformRow : platformRows) {
            operationRepository.save(BaySpecialtyOperationEntity.builder()
                    .bayType(platformRow.bayType())
                    .operationCode(platformRow.operationCode())
                    .build());
        }
        log.info(
                "Provisioned the bay specialty map for tenant {} from the platform template ({} rows)",
                tenantId,
                platformRows.size());
        publisher.publishChanged(tenantId);
        return true;
    }

    /** One platform-tenant specialty-map row, read before the target tenant is bound. */
    public record PlatformRow(
            @NonNull String bayType, @NonNull String operationCode) {}
}
