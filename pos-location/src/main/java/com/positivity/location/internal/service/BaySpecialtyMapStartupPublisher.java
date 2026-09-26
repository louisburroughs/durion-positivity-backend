package com.positivity.location.internal.service;

import com.positivity.tenancy.TenantIterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Republishes every active tenant's bay specialty map once at startup (DECISION-LOCATION-025), so a
 * consumer replica standing up for the first time, or one that missed live traffic, converges
 * without a manual replay — the same role {@code LocationEventTypeInitializer} plays for event-type
 * registration, but over Kafka facts instead of the REST registration call.
 *
 * <p>{@link TenantIterator#forEachActiveTenant} already logs and continues past a single tenant's
 * failure, and never visits {@code PlatformTenant.ID} (it is control-plane data, not an ordinary
 * tenant's map); the try/catch here is the same "never block startup" belt-and-suspenders every
 * other startup integration in this module uses.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class BaySpecialtyMapStartupPublisher implements ApplicationRunner {

    private final TenantIterator tenantIterator;
    private final BaySpecialtyMapPublisher publisher;

    @Override
    public void run(ApplicationArguments args) {
        try {
            int published = tenantIterator.forEachActiveTenant(publisher::publishCurrent);
            log.info("Published the bay specialty map for {} tenant(s) at startup", published);
        } catch (Exception e) {
            log.warn("Bay specialty map startup publish failed; continuing startup", e);
        }
    }
}
