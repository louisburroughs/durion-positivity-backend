package com.positivity.workorder.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.service.DocumentNumberAllocator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * #2342: first use of a number scope by several concurrent requests must not need a second pooled
 * connection per request. The pool is pinned below the number of racing threads, so a provisioner
 * that opened a {@code REQUIRES_NEW} transaction while the caller held its connection would starve
 * the pool and every request would fail at the connection timeout. Needs real Postgres.
 */
@DisplayName("First use of a document number scope does not deadlock a small pool (#2342)")
class DocumentNumberAllocatorConcurrencyIT extends PostgresTenancyTestBase {

    private static final int THREADS = 6;

    @DynamicPropertySource
    static void smallPool(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
    }

    @Autowired
    private DocumentNumberAllocator allocator;

    @Autowired
    private TransactionTemplate transactions;

    @Test
    void concurrentFirstAllocationsInAFreshScopeAllSucceedWithConsecutiveNumbers() throws Exception {
        String scope = "WO-" + UUID.randomUUID().toString().substring(0, 8);
        String prefix = scope + "-";
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        List<Long> numbers = new ArrayList<>();
        try {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return asTenant(
                            TENANT_A,
                            () -> transactions.execute(
                                    status -> allocator.allocate(scope, prefix, 1000L, candidate -> false)));
                }));
            }
            start.countDown();
            for (Future<String> future : futures) {
                numbers.add(Long.parseLong(future.get(30, TimeUnit.SECONDS).substring(prefix.length())));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(numbers).as("no request lost the pool or the race").hasSize(THREADS);
        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(numbers.stream().sorted().toList()).containsExactly(1000L, 1001L, 1002L, 1003L, 1004L, 1005L);
        Integer rows = new JdbcTemplate(ownerDataSource())
                .queryForObject(
                        "SELECT count(*) FROM document_number_sequence WHERE scope_key = ?", Integer.class, scope);
        assertThat(rows).as("exactly one sequence row for the scope").isEqualTo(1);
    }
}
