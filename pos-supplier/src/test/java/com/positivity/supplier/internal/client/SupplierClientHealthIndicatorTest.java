package com.positivity.supplier.internal.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.internal.domain.model.SupplierCapability;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * The one guarantee this indicator exists to provide: <strong>breaker state never degrades the
 * health status</strong>.
 *
 * <p>Why that is worth a dedicated test rather than a comment. {@code docker-compose.yml} points
 * pos-supplier's container healthcheck at {@code /actuator/health}, and {@code application.yml}
 * declares no health groups, so every contributor lands in the aggregate status. If an open breaker
 * reported DOWN, one unreachable vendor would mark the container unhealthy, Docker would restart it,
 * and dependents gating on {@code service_healthy} would refuse to start — handing any vendor a
 * restart lever over our own service. A vendor outage is the expected steady state a breaker exists
 * to absorb, not a statement that this pod is unserviceable.
 *
 * <p>resilience4j's {@code registerHealthIndicator} maps OPEN to DOWN by default, so this test also
 * guards against someone later "simplifying" this class into that default.
 */
class SupplierClientHealthIndicatorTest {

    private static final UUID PROFILE_A = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID PROFILE_B = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");

    private final SupplierBreakerRegistry breakers = new SupplierBreakerRegistry(50f, 10, 5, 30, 3);
    private final SupplierClientHealthIndicator indicator = new SupplierClientHealthIndicator(breakers);

    @Test
    void reportsUpWithNoBreakersYet() {
        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("breakerCount", 0);
    }

    @Test
    void anOpenBreakerDoesNotMakeTheServiceDown() {
        CircuitBreaker breaker = breakers.breakerFor(PROFILE_A, SupplierCapability.STOCK_INQUIRY);
        breaker.transitionToOpenState();

        Health health = indicator.health();

        assertThat(health.getStatus())
                .as("an open breaker must NEVER report DOWN: the container healthcheck reads the"
                        + " aggregate status, so DOWN would let one vendor's outage restart this service")
                .isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("openBreakers", 1L);
    }

    @Test
    void everyBreakerOpenStillReportsUp() {
        // The worst realistic case: every configured vendor is unreachable. pos-supplier still
        // serves its admin API and audit reads, so it is still serviceable.
        breakers.breakerFor(PROFILE_A, SupplierCapability.STOCK_INQUIRY).transitionToOpenState();
        breakers.breakerFor(PROFILE_A, SupplierCapability.ORDER_CREATE).transitionToOpenState();
        breakers.breakerFor(PROFILE_B, SupplierCapability.PRICE_CATALOG).transitionToOpenState();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("breakerCount", 3).containsEntry("openBreakers", 3L);
    }

    @Test
    void aForcedOpenBreakerIsCountedAsOpenButStillUp() {
        breakers.breakerFor(PROFILE_A, SupplierCapability.INVOICE_FETCH).transitionToForcedOpenState();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("openBreakers", 1L);
    }

    @Test
    void halfOpenBreakersAreReportedSeparately() {
        CircuitBreaker breaker = breakers.breakerFor(PROFILE_A, SupplierCapability.WORKORDER_AUTHORIZATION);
        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("halfOpenBreakers", 1L).containsEntry("openBreakers", 0L);
    }

    /**
     * The guarantee must survive the indicator itself failing. Without a try/catch, an exception here
     * becomes a 500 from /actuator/health, and the container healthcheck's `wget` exits non-zero on a
     * 500 exactly as on a DOWN body — so the restart hazard returns by a different door, and every
     * other health contributor is lost with it.
     */
    @Test
    void aThrowingBreakerRegistryStillReportsUpWithAMarker() {
        SupplierClientHealthIndicator failing =
                new SupplierClientHealthIndicator(new SupplierBreakerRegistry(50f, 10, 5, 30, 3) {
                    @Override
                    public java.util.SortedMap<String, String> breakerStates() {
                        throw new IllegalStateException("registry exploded");
                    }
                });

        Health health = failing.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsKey("detailsUnavailable")
                .containsEntry("statusPolicy", SupplierClientHealthIndicator.STATUS_POLICY);
    }

    @Test
    void detailsExposeBreakerStatesKeyedByProfileAndCapability() {
        breakers.breakerFor(PROFILE_A, SupplierCapability.STOCK_INQUIRY).transitionToOpenState();

        @SuppressWarnings("unchecked")
        Map<String, String> states =
                (Map<String, String>) indicator.health().getDetails().get("breakers");

        assertThat(states).containsEntry("supplier." + PROFILE_A + ".STOCK_INQUIRY", "OPEN");
    }

    /**
     * {@code show-details: when-authorized} means these details are reachable over HTTP, so they
     * must contain no base URL, credential, or resolved secret — only ids, capability names, states.
     */
    @Test
    void detailsCarryNoCredentialOrEndpointMaterial() {
        breakers.breakerFor(PROFILE_A, SupplierCapability.ORDER_CREATE).transitionToOpenState();

        String rendered = indicator.health().getDetails().toString();

        assertThat(rendered)
                .doesNotContain("http://")
                .doesNotContain("https://")
                .doesNotContain("Bearer ")
                .doesNotContain("Basic ")
                .doesNotContain("env:")
                .doesNotContain("apikey");
    }

    @Test
    void breakersAreScopedPerProfileAndCapabilityNotShared() {
        // One vendor's failing capability must not fast-fail another capability or another vendor.
        CircuitBreaker stockA = breakers.breakerFor(PROFILE_A, SupplierCapability.STOCK_INQUIRY);
        CircuitBreaker orderA = breakers.breakerFor(PROFILE_A, SupplierCapability.ORDER_CREATE);
        CircuitBreaker stockB = breakers.breakerFor(PROFILE_B, SupplierCapability.STOCK_INQUIRY);
        stockA.transitionToOpenState();

        assertThat(stockA.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(orderA.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(stockB.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void theSameKeyReturnsTheSameBreakerInstanceSoStateSurvivesAcrossCalls() {
        CircuitBreaker first = breakers.breakerFor(PROFILE_A, SupplierCapability.PRICE_CATALOG);
        first.transitionToOpenState();
        CircuitBreaker second = breakers.breakerFor(PROFILE_A, SupplierCapability.PRICE_CATALOG);

        assertThat(second).isSameAs(first);
        assertThat(second.getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }
}
