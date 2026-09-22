package com.positivity.workorder.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.tenancy.TenantContext;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import com.positivity.workorder.internal.service.EstimateService;
import com.positivity.workorder.internal.service.WorkorderService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * #2150: concurrent estimate creates at one location, and concurrent workorder creates in one
 * tenant, each get a distinct number. Before the counter, overlapping creates probed the same free
 * number and all but one failed its unique constraint as a 500. Needs real Postgres: the fix is a
 * {@code FOR UPDATE} row lock, which H2 does not reproduce faithfully.
 */
@DisplayName("Concurrent estimate and workorder creates draw distinct numbers (#2150)")
class ConcurrentDocumentNumberIT extends PostgresTenancyTestBase {

    private static final int CREATES = 8;

    @Autowired
    private EstimateService estimateService;

    @Autowired
    private WorkorderService workorderService;

    @Autowired
    private ExtCustomerPartyReplicaRepository customerReplicas;

    @Autowired
    private WorkorderRepository workorders;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyConcurrentCreateAtOneLocationSucceedsWithItsOwnNumber() throws Exception {
        UUID locationId = UUID.randomUUID();

        List<String> numbers = raceAsTenantA(() ->
                estimateService.createEstimate(request(locationId), "it-user").getEstimateNumber());

        assertThat(numbers).as("no create lost the race").hasSize(CREATES).doesNotHaveDuplicates();
        assertThat(numbers)
                .as("the counter hands out consecutive numbers from 1000")
                .allMatch(n -> n.matches("EST-\\d{4}-10\\d\\d"));
    }

    /**
     * Workorder numbers are unique per tenant, so every estimate-less create in the tenant draws from
     * the one WO-{year} counter. Before it, they all probed WO-YYYY-1000 and collided on
     * ux_workorder_workorder_number.
     */
    @Test
    void everyConcurrentEstimateLessWorkorderCreateSucceedsWithItsOwnNumber() throws Exception {
        UUID customerId = UUID.randomUUID();
        asTenant(
                TENANT_A,
                () -> customerReplicas.saveAndFlush(ExtCustomerPartyReplica.builder()
                        .partyId(customerId)
                        .partyType("PERSON")
                        .status("ACTIVE")
                        .requirementsMet(true)
                        .aggregateVersion(1L)
                        .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build()));

        List<UUID> ids = raceAsTenantA(
                () -> workorderService.createWorkorder(null, customerId).getId());
        List<String> numbers = asTenant(
                TENANT_A,
                () -> workorders.findAllById(ids).stream()
                        .map(Workorder::getWorkorderNumber)
                        .toList());

        assertThat(numbers).as("no create lost the race").hasSize(CREATES).doesNotHaveDuplicates();
        assertThat(numbers).allMatch(n -> n.matches("WO-\\d{4}-\\d+"));
    }

    /**
     * Runs {@code CREATES} copies of {@code work} as tenant A and an authenticated caller, released
     * together, and returns their results.
     */
    private static <T> List<T> raceAsTenantA(Callable<T> work) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(CREATES);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < CREATES; i++) {
                futures.add(pool.submit(() -> {
                    // Workorder creation resolves the caller's primary location from the security
                    // context, so each worker needs an authenticated caller of its own.
                    UsernamePasswordAuthenticationToken caller =
                            new UsernamePasswordAuthenticationToken("it-user", "n/a", List.of());
                    caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "it-user"));
                    SecurityContextHolder.getContext().setAuthentication(caller);
                    try {
                        start.await();
                        return asTenant(TENANT_A, work);
                    } finally {
                        SecurityContextHolder.clearContext();
                    }
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private static CreateEstimateRequest request(UUID locationId) {
        CreateEstimateRequest request = new CreateEstimateRequest();
        request.setCustomerId(UUID.randomUUID());
        request.setVehicleId(UUID.randomUUID());
        request.setLocationId(locationId);
        return request;
    }
}
