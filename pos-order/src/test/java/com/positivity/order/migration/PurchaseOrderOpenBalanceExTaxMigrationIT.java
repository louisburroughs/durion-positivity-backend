package com.positivity.order.migration;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.order.OrderPostgresContainer;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * V10 (#2633): purchase orders written before the ex-tax basis carried their tax in the open balance. The
 * migration takes {@code tax_minor} off {@code open_balance_minor}, floored at zero, on every order that is not
 * {@code CANCELLED}, and leaves untaxed orders, cancelled orders and a missing balance alone.
 */
@DisplayName("V10: purchase order open balance moved to the ex-tax basis (#2633)")
class PurchaseOrderOpenBalanceExTaxMigrationIT {

    private static final UUID TENANT = UUID.fromString("00000000-0000-7000-8000-0000000c2633");
    private static final UUID OTHER_TENANT = UUID.fromString("00000000-0000-7000-8000-0000000d2633");

    @Test
    @DisplayName("the tax comes off every non-cancelled balance, floored at zero")
    void movesExistingBalancesOntoTheExTaxBasis() {
        DataSource database = OrderPostgresContainer.ownerDataSource("purchase-order-open-balance-ex-tax");
        JdbcTemplate jdbc = new JdbcTemplate(database);
        // The database as S32d (#2639) left it: V1-V9.
        Flyway.configure()
                .placeholders(Map.of("functional_currency", "USD"))
                .dataSource(database)
                .locations("classpath:db/migration")
                .target("9")
                .load()
                .migrate();
        UUID approved = order(jdbc, "APPROVED", 100_000L, 10_000L, 110_000L);
        UUID partlyReceived = order(jdbc, "PARTIALLY_RECEIVED", 100_000L, 10_000L, 80_000L);
        UUID fullyReceived = order(jdbc, "FULLY_RECEIVED", 100_000L, 10_000L, 10_000L);
        UUID taxAboveBalance = order(jdbc, "PARTIALLY_RECEIVED", 100_000L, 10_000L, 4_000L);
        UUID cancelled = order(jdbc, "CANCELLED", 100_000L, 10_000L, 110_000L);
        UUID untaxedDraft = order(jdbc, "DRAFT", 50_000L, 0L, 50_000L);
        UUID noBalance = order(jdbc, "DRAFT", 50_000L, 5_000L, null);
        // Flyway runs as the owner with no tenant bound, so every tenant's rows move, not one.
        UUID otherTenantApproved = order(jdbc, OTHER_TENANT, "APPROVED", 100_000L, 10_000L, 110_000L);

        Flyway.configure()
                .placeholders(Map.of("functional_currency", "USD"))
                .dataSource(database)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(balance(jdbc, approved)).isEqualTo(100_000L);
        assertThat(balance(jdbc, partlyReceived)).isEqualTo(70_000L);
        assertThat(balance(jdbc, fullyReceived))
                .as("a fully received taxed order reaches zero")
                .isZero();
        assertThat(balance(jdbc, taxAboveBalance)).as("floored at zero").isZero();
        assertThat(balance(jdbc, cancelled))
                .as("cancelled orders are left as they are")
                .isEqualTo(110_000L);
        assertThat(balance(jdbc, untaxedDraft)).isEqualTo(50_000L);
        assertThat(balance(jdbc, noBalance)).isNull();
        assertThat(balance(jdbc, OTHER_TENANT, otherTenantApproved)).isEqualTo(100_000L);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM purchase_order WHERE tenant_id = ? AND grand_total_minor <> subtotal_minor"
                                + " + tax_minor",
                        Long.class,
                        TENANT))
                .as("the document totals are untouched")
                .isZero();
    }

    private static UUID order(
            JdbcTemplate jdbc, String status, long subtotalMinor, long taxMinor, Long openBalanceMinor) {
        return order(jdbc, TENANT, status, subtotalMinor, taxMinor, openBalanceMinor);
    }

    private static UUID order(
            JdbcTemplate jdbc, UUID tenantId, String status, long subtotalMinor, long taxMinor, Long openBalanceMinor) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO purchase_order (tenant_id, purchase_order_id, po_number, vendor_id, status, version_number,"
                        + " currency, subtotal_minor, tax_minor, grand_total_minor, open_balance_minor, created_by,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, 'USD', ?, ?, ?, ?, 'buyer',"
                        + " TIMESTAMPTZ '2026-10-01 09:00:00+00', TIMESTAMPTZ '2026-10-01 09:00:00+00')",
                tenantId,
                id,
                "PO-" + id,
                UUID.randomUUID(),
                status,
                subtotalMinor,
                taxMinor,
                subtotalMinor + taxMinor,
                openBalanceMinor);
        return id;
    }

    private static Long balance(JdbcTemplate jdbc, UUID purchaseOrderId) {
        return balance(jdbc, TENANT, purchaseOrderId);
    }

    private static Long balance(JdbcTemplate jdbc, UUID tenantId, UUID purchaseOrderId) {
        return jdbc.queryForObject(
                "SELECT open_balance_minor FROM purchase_order WHERE tenant_id = ? AND purchase_order_id = ?",
                Long.class,
                tenantId,
                purchaseOrderId);
    }
}
