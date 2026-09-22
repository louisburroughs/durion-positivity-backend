package com.positivity.workorder.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.tenancy.TenantContext;
import com.positivity.workorder.internal.dto.CreateEstimateRequest;
import com.positivity.workorder.internal.dto.EstimateResponse;
import com.positivity.workorder.internal.service.EstimateService;
import java.util.ArrayList;
import java.util.List;
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

/**
 * #2150: concurrent estimate creates at one location each get a distinct number. Before the
 * counter, creates that overlapped probed the same free EST-YYYY-NNNN and all but one failed the
 * (tenant_id, location_id, estimate_number) unique constraint as a 500. Needs real Postgres: the
 * fix is a {@code FOR UPDATE} row lock, which H2 does not reproduce faithfully.
 */
@DisplayName("Concurrent estimate creates draw distinct numbers (#2150)")
class ConcurrentDocumentNumberIT extends PostgresTenancyTestBase {

    private static final int CREATES = 8;

    @Autowired
    private EstimateService estimateService;

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void everyConcurrentCreateAtOneLocationSucceedsWithItsOwnNumber() throws Exception {
        UUID locationId = UUID.randomUUID();
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<EstimateResponse>> creates = new ArrayList<>();
        for (int i = 0; i < CREATES; i++) {
            creates.add(() -> {
                start.await();
                return asTenant(TENANT_A, () -> estimateService.createEstimate(request(locationId), "it-user"));
            });
        }

        ExecutorService pool = Executors.newFixedThreadPool(CREATES);
        try {
            List<Future<EstimateResponse>> futures = new ArrayList<>();
            for (Callable<EstimateResponse> create : creates) {
                futures.add(pool.submit(create));
            }
            start.countDown();

            List<String> numbers = new ArrayList<>();
            for (Future<EstimateResponse> future : futures) {
                numbers.add(future.get(60, TimeUnit.SECONDS).getEstimateNumber());
            }

            assertThat(numbers).as("no create lost the race").hasSize(CREATES).doesNotHaveDuplicates();
            assertThat(numbers)
                    .as("the counter hands out consecutive numbers from 1000")
                    .allMatch(n -> n.matches("EST-\\d{4}-10\\d\\d"));
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
