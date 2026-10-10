package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.PostgresCommittingTestBase;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Goods-receipt creates on a connection pool smaller than the number of concurrent writers (#2501).
 *
 * <p>A goods-receipt bill takes its number under the tenant's {@code accounting_sequence} row lock
 * and keeps that lock until its transaction ends, so writers in one tenant queue on it, each holding
 * a pooled connection. Anything the lock holder does on a <em>second</em> connection while it holds
 * the lock therefore waits behind the writers that are waiting for it: with a pool smaller than the
 * writers ({@code maximum-pool-size 3} here) the holder gets no connection until the pool's timeout,
 * and for that long nobody in any tenant does. The retired vendor-directory write
 * ran in a {@code REQUIRES_NEW} transaction of its own and was such a call (S24 replaced it with a read
 * of the vendor copy, made before the number is drawn). The same defect was removed from the counter's
 * own bootstrap in #2342.
 *
 * <p>The whole production wiring runs here, GL posting hook included, so any other call between the
 * number and the commit that reached for a second connection would fail this test the same way.
 *
 * <p>Commits, so it has a database of its own. Requires Docker.
 */
@DisplayName("Goods-receipt bills on a pool smaller than the number of writers (#2501, real Postgres)")
class VendorBillGoodsReceiptSmallPoolIT extends PostgresCommittingTestBase {

    /** Smaller than {@link #WRITERS}, so writers queue for connections as a burst does on any pool it outgrows. */
    private static final int POOL_SIZE = 3;

    private static final int WRITERS = 2 * POOL_SIZE;

    /**
     * How long a thread waits for a pooled connection before Hikari gives up. Every writer finishing
     * well inside it is the proof that none of them ever waited for a connection it could not get.
     * Generous, so that six serialised creates with GL posting on a slow runner stay inside it: the
     * proof is elapsed below the timeout, not how fast the creates are.
     */
    private static final Duration CONNECTION_TIMEOUT = Duration.ofSeconds(30);

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        AccountingPostgresContainer.registerIsolatedDatabase(registry, "vendor-bill-small-pool");
        registerCommonProperties(registry);
        // This context's own pool; application-pg.yml and every other test context keep theirs.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.minimum-idle", () -> 1);
        registry.add("spring.datasource.hikari.connection-timeout", CONNECTION_TIMEOUT::toMillis);
    }

    @Autowired
    private VendorBillService vendorBillService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** The counter stands where the previous test left it; each test reads it before it starts. */
    private long firstNumber;

    @BeforeEach
    void readCounter() {
        // One bill first: the tenant's counter row, the accounting period and the warm code paths
        // exist before the clock starts, so the timing below measures the creates and nothing else.
        firstNumber =
                sequenceOf(create(copiedVendor(), Transactions.SERVICE_OWN).getBillNumber()) + 1;
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Transactions.class)
    @DisplayName(
            "six writers on three connections: every bill is created naming its vendor from the copy, numbered consecutively, with no wait for a connection")
    void writersOutnumberingThePoolAllSucceedWithoutWaitingForAConnection(Transactions transactions) throws Exception {
        List<UUID> vendors = new ArrayList<>();
        for (int writer = 0; writer < WRITERS; writer++) {
            vendors.add(copiedVendor());
        }
        ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<VendorBillResponse>> creating = new ArrayList<>();
            for (UUID vendor : vendors) {
                creating.add(pool.submit(() -> {
                    start.await();
                    return create(vendor, transactions);
                }));
            }
            long began = System.nanoTime();
            start.countDown();
            List<VendorBillResponse> created = new ArrayList<>();
            for (Future<VendorBillResponse> writer : creating) {
                // A writer that could not get a connection fails here with the pool's timeout.
                created.add(writer.get(3 * CONNECTION_TIMEOUT.toSeconds(), TimeUnit.SECONDS));
            }
            Duration elapsed = Duration.ofNanos(System.nanoTime() - began);

            assertThat(created).hasSize(WRITERS);
            assertThat(created.stream().map(bill -> sequenceOf(bill.getBillNumber())))
                    .as("distinct and gap-free after the warm-up bill")
                    .containsExactlyInAnyOrderElementsOf(LongStream.range(firstNumber, firstNumber + WRITERS)
                            .boxed()
                            .toList());
            assertThat(elapsed)
                    .as("a single wait for a connection that never comes lasts the pool's timeout")
                    .isLessThan(CONNECTION_TIMEOUT);
            // S24: each bill names its vendor as the copy does, read on the bill's own connection.
            for (UUID vendor : vendors) {
                assertThat(jdbc.queryForList(
                                "SELECT vendor_name FROM vendor_bill WHERE vendor_id = ? AND tenant_id = ?",
                                String.class,
                                vendor,
                                TENANT))
                        .as("bill of vendor " + vendor)
                        .containsExactly(nameOf(vendor));
            }
            assertThat(jdbc.queryForObject(
                            "SELECT next_value FROM accounting_sequence WHERE tenant_id = ? AND scope_key = ?",
                            Long.class,
                            TENANT,
                            "BILL-" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMM"))))
                    .isEqualTo(firstNumber + WRITERS);
        } finally {
            pool.shutdownNow();
        }
    }

    /** Whether the service begins the transaction itself or joins one a caller opened. */
    enum Transactions {
        /** The REST path: the service's own TransactionTemplate. */
        SERVICE_OWN,
        /** The in-JVM event path: VendorBillEventHandler.onGoodsReceived is @Transactional. */
        CALLERS
    }

    private VendorBillResponse create(UUID vendor, Transactions transactions) {
        GoodsReceivedEvent event = GoodsReceivedEvent.builder()
                .eventId(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .purchaseOrderId(UUID.randomUUID())
                .vendorId(vendor)
                .vendorName("Vendor " + vendor.toString().substring(0, 8))
                .receivedDate(LocalDateTime.of(2026, 10, 1, 9, 30))
                .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                        .productId(UUID.randomUUID())
                        .description("Brake pads")
                        .quantity(new BigDecimal("10"))
                        .unitPrice(new BigDecimal("24.99"))
                        .isInventoryItem(true)
                        .build()))
                .build();
        return TenantTestSupport.asTenant(TENANT, () -> switch (transactions) {
            case SERVICE_OWN -> vendorBillService.handleGoodsReceivedEvent(event);
            case CALLERS ->
                new TransactionTemplate(transactionManager)
                        .execute(_ -> vendorBillService.handleGoodsReceivedEvent(event));
        });
    }

    /** A new active vendor in the tenant's copy (S24): a goods-receipt bill must name one. */
    private UUID copiedVendor() {
        UUID vendor = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO ext_supplier_vendor (tenant_id, vendor_id, vendor_number, display_name, status,"
                        + " remit_to_version, tax_registrations, created_by, aggregate_version, updated_at)"
                        + " VALUES (?, ?, ?, ?, 'ACTIVE', 0, '[]'::jsonb, 'buyer.ben', 1, now())",
                TENANT,
                vendor,
                "V-" + vendor.toString().substring(0, 8),
                nameOf(vendor));
        return vendor;
    }

    private static String nameOf(UUID vendor) {
        return "Vendor " + vendor.toString().substring(0, 8);
    }

    private static long sequenceOf(String billNumber) {
        Matcher number = Pattern.compile("BILL_[0-9A-F]{8}_\\d{8}_(\\d{7})").matcher(billNumber);
        assertThat(number.matches()).as(billNumber).isTrue();
        return Long.parseLong(number.group(1));
    }
}
