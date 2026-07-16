package com.positivity.warranty.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.warranty.internal.repository.ClaimCodeSequenceRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.Year;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * PRD §4: {@code claimCode} is a unique per-year business identifier. Correctness under
 * concurrent intake hangs entirely on {@code @Lock(PESSIMISTIC_WRITE)} on
 * {@code ClaimCodeSequenceRepository.lockByYear} — this test allocates from many threads in
 * real, separate transactions (no test-managed transaction), so dropping the lock annotation
 * produces duplicate codes and turns it red. Also covers the unseeded-year insert fallback
 * (years past the V1 seed range 2024–2075).
 */
@DataJpaTest(
        properties = {
            // LOCK_TIMEOUT generously sized: threads legitimately queue on the year row.
            "spring.datasource.url=jdbc:h2:mem:pos_warranty_claimcode;MODE=PostgreSQL;LOCK_TIMEOUT=10000;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=validate"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ClaimCodeServiceImpl.class, ClaimCodeConcurrencyTest.ClockConfig.class})
class ClaimCodeConcurrencyTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    private static final int THREADS = 6;
    private static final int ALLOCATIONS_PER_THREAD = 10;

    @Autowired
    private ClaimCodeService claimCodeService;

    @Autowired
    private ClaimCodeSequenceRepository claimCodeSequenceRepository;

    /**
     * Runs outside any test-managed transaction so every {@code nextClaimCode()} call opens —
     * and commits — its own transaction, exactly like concurrent claim-intake requests.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentAllocationsYieldDistinctContiguousCodes() throws Exception {
        int total = THREADS * ALLOCATIONS_PER_THREAD;
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<List<String>>> futures = java.util.stream.IntStream.range(0, THREADS)
                    .mapToObj(t -> pool.submit(() -> {
                        startGate.await();
                        List<String> codes = new java.util.ArrayList<>();
                        for (int i = 0; i < ALLOCATIONS_PER_THREAD; i++) {
                            codes.add(claimCodeService.nextClaimCode());
                        }
                        return codes;
                    }))
                    .toList();
            startGate.countDown();

            List<String> allCodes = new java.util.ArrayList<>();
            for (Future<List<String>> future : futures) {
                allCodes.addAll(future.get(60, TimeUnit.SECONDS));
            }

            int year = Year.now(Clock.systemUTC()).getValue();
            assertThat(allCodes).hasSize(total).allMatch(code -> code.matches("WC-" + year + "-\\d{6}"));
            Set<Long> sequences = allCodes.stream()
                    .map(code -> Long.parseLong(code.substring(code.lastIndexOf('-') + 1)))
                    .collect(Collectors.toSet());
            // Distinct: a dropped PESSIMISTIC_WRITE lock hands the same number to two threads.
            assertThat(sequences).hasSize(total);
            // Contiguous: no allocation was lost to a rolled-back or clobbered increment.
            long min = sequences.stream().mapToLong(Long::longValue).min().orElseThrow();
            long max = sequences.stream().mapToLong(Long::longValue).max().orElseThrow();
            assertThat(max - min).isEqualTo(total - 1L);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * Years beyond the V1 seed range (2024–2075) have no counter row: the first allocation
     * must create it and hand out {@code WC-<year>-000001}. Runs in the default rolled-back
     * test transaction; the service is instantiated directly with a 2080 clock because the
     * shared bean's clock must stay at the current (seeded) year for the concurrency test.
     */
    @Test
    void unseededYearFallbackCreatesTheCounterRowAndStartsAtOne() {
        Clock year2080 = Clock.fixed(Instant.parse("2080-01-01T00:00:00Z"), ZoneOffset.UTC);
        ClaimCodeServiceImpl service = new ClaimCodeServiceImpl(year2080, claimCodeSequenceRepository);

        assertThat(service.nextClaimCode()).isEqualTo("WC-2080-000001");
        assertThat(service.nextClaimCode()).isEqualTo("WC-2080-000002");
        assertThat(claimCodeSequenceRepository.findById(2080))
                .isPresent()
                .get()
                .satisfies(row -> assertThat(row.getNextSeq()).isEqualTo(3L));
    }
}
