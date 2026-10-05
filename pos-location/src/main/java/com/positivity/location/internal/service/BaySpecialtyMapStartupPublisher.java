package com.positivity.location.internal.service;

import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.TenantIterator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Republishes every active tenant's bay specialty map once at startup (DECISION-LOCATION-025), so a
 * consumer replica standing up for the first time, or one that missed live traffic, converges
 * without a manual replay — the same role {@code LocationEventTypeInitializer} plays for event-type
 * registration, but over Kafka facts instead of the REST registration call.
 *
 * <p><strong>Also backfills a tenant this module never provisioned</strong>: a tenant created
 * before {@code TenantEventsListener} existed (or whose {@code tenant.created} this module never
 * saw) would otherwise have an empty map forever, since nothing but that listener writes
 * {@code bay_specialty_operation} rows. For each active tenant this sweep checks emptiness first
 * ({@code BaySpecialtyMapProvisioningService.provisionIfMissing}, the same copy-from-platform-
 * template routine the listener uses, minus the {@code tenant.created} eventId there is none of
 * here) and copies-and-publishes exactly once when the tenant is empty; a tenant that already has
 * rows is left untouched and simply republished at its current version.
 *
 * <p>{@link TenantIterator#forEachActiveTenant} already logs and continues past a single tenant's
 * failure, and never visits {@code PlatformTenant.ID} (it is control-plane data, not an ordinary
 * tenant's map); the try/catch here is the same "never block startup" belt-and-suspenders every
 * other startup integration in this module uses. The platform template itself is read once, before
 * the sweep starts, rather than once per tenant.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@KafkaRails
public class BaySpecialtyMapStartupPublisher implements ApplicationRunner {

    private final TenantIterator tenantIterator;
    private final BaySpecialtyMapPublisher publisher;
    private final BaySpecialtyMapProvisioningService provisioningService;

    @Override
    public void run(ApplicationArguments args) {
        try {
            List<BaySpecialtyMapProvisioningService.PlatformRow> platformRows =
                    provisioningService.readPlatformTemplate();
            int published = tenantIterator.forEachActiveTenant(tenantId -> {
                boolean backfilled = provisioningService.provisionIfMissing(tenantId, platformRows);
                if (!backfilled) {
                    publisher.publishCurrent(tenantId);
                }
            });
            log.info("Published the bay specialty map for {} tenant(s) at startup", published);
        } catch (Exception e) {
            log.warn("Bay specialty map startup publish failed; continuing startup", e);
        }
    }
}
