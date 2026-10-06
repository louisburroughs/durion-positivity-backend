package com.positivity.supplier.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.SupplierPostgresContainer;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * AC 8 of #2516: {@code V3__vendor_master} on a database that already holds profiles. Every
 * profile gets an ACTIVE vendor of its own tenant, {@code supplier_profile.vendor_id} ends
 * {@code NOT NULL}, and the profile's exchange-audit and invoice rows are untouched.
 *
 * <p>Runs on a database of its own in the shared container, migrated to V2, seeded, then migrated
 * to the head — the one way to exercise a backfill, which only means anything on a database that
 * already ran the earlier versions.
 */
@ResourceLock(SupplierPostgresContainer.RESOURCE_LOCK)
@DisplayName("V3 vendor master migration backfills existing profiles (#2516 AC 8)")
class VendorMasterMigrationIT {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-00000000000b");

    private PGSimpleDataSource owner;
    private PGSimpleDataSource database;
    private String databaseName;

    @BeforeEach
    void createDatabase() throws SQLException {
        owner = (PGSimpleDataSource) SupplierPostgresContainer.ownerDataSource();
        databaseName = "supplier_v3_backfill_" + Long.toHexString(System.nanoTime());
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + databaseName);
        }
        database = new PGSimpleDataSource();
        database.setURL(owner.getURL().replaceFirst("/[^/?]+(\\?|$)", "/" + databaseName + "$1"));
        database.setUser(owner.getUser());
        database.setPassword(owner.getPassword());
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (Connection connection = owner.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        }
    }

    private Flyway flyway(String target) {
        var configuration = Flyway.configure().dataSource(database).locations("classpath:db/migration");
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration.load();
    }

    private static UUID profile(Statement statement, UUID tenantId, String supplierRef, String displayName)
            throws SQLException {
        UUID id = UUID.randomUUID();
        statement.execute("INSERT INTO supplier_profile (tenant_id, vendor_profile_id, supplier_ref, display_name,"
                + " enabled, sandbox, source_of_truth, created_at, updated_at, version) VALUES ('" + tenantId + "', '"
                + id + "', '" + supplierRef + "', '" + displayName + "', TRUE, FALSE, 'ADMIN', now(), now(), 0)");
        return id;
    }

    @Test
    @DisplayName("each profile gets an ACTIVE vendor of its tenant; vendor_id is NOT NULL; history is untouched")
    void backfillsOneVendorPerProfile() throws SQLException {
        flyway("2").migrate();

        UUID michelinA;
        UUID michelinDuplicateA;
        UUID oddRefA;
        UUID michelinB;
        UUID acme;
        UUID acmeUpper;
        UUID acmeTwo;
        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement()) {
            michelinA = profile(statement, TENANT_A, "michelin-eu", "Michelin Europe");
            // Folds to the same number as michelin-eu: the second gets a suffix so the tenant key holds.
            michelinDuplicateA = profile(statement, TENANT_A, "michelin_eu", "Michelin Europe (old)");
            // Folds to a number that would start with '-': prefixed so it satisfies the number shape.
            oddRefA = profile(statement, TENANT_A, "_legacy", "Legacy Supplier");
            // The same supplierRef in another tenant: numbers are unique per tenant only.
            michelinB = profile(statement, TENANT_B, "michelin-eu", "Michelin Europe");
            // Review case: ACME and acme fold to ACME; the second must not take ACME-2, which acme-2 holds.
            acme = profile(statement, TENANT_A, "acme", "Acme");
            acmeUpper = profile(statement, TENANT_A, "ACME", "Acme (upper)");
            acmeTwo = profile(statement, TENANT_A, "acme-2", "Acme Two");
            statement.execute("INSERT INTO supplier_exchange_audit (tenant_id, exchange_audit_id, vendor_profile_id,"
                    + " supplier_ref, capability, protocol_family, protocol_version, http_method, endpoint_uri,"
                    + " attempt, correlation_id, outcome, started_at, duration_ms, capture_level, created_at)"
                    + " VALUES ('" + TENANT_A + "', gen_random_uuid(), '" + michelinA + "', 'michelin-eu',"
                    + " 'INVOICE_FETCH', 'EDIWHEEL_B', 'B3_3', 'POST', 'https://edi.example/invoices', 1, 'c-1', 'OK',"
                    + " now(), 10, 'METADATA_ONLY', now())");
            statement.execute("INSERT INTO supplier_invoice (tenant_id, supplier_invoice_id, vendor_profile_id,"
                    + " supplier_ref, vendor_invoice_number, invoice_date, invoice_type, currency, fetched_at,"
                    + " created_at, updated_at) VALUES ('" + TENANT_A + "', gen_random_uuid(), '" + michelinA
                    + "', 'michelin-eu', 'INV-1', DATE '2026-09-30', 'INVOICE', 'EUR', now(), now(), now())");
        }

        flyway(null).migrate();

        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement()) {
            Map<UUID, String> numbers = new HashMap<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT p.vendor_profile_id, p.tenant_id, v.tenant_id AS"
                            + " vendor_tenant, v.vendor_number, v.status, v.legal_name, v.display_name, v.remit_to,"
                            + " v.remit_to_version FROM supplier_profile p JOIN supplier_vendor v ON v.vendor_id = p.vendor_id")) {
                while (rows.next()) {
                    assertThat(rows.getObject("vendor_tenant")).isEqualTo(rows.getObject("tenant_id"));
                    assertThat(rows.getString("status")).isEqualTo("ACTIVE");
                    assertThat(rows.getString("remit_to")).isNull();
                    assertThat(rows.getInt("remit_to_version")).isZero();
                    assertThat(rows.getString("legal_name")).isEqualTo(rows.getString("display_name"));
                    numbers.put((UUID) rows.getObject("vendor_profile_id"), rows.getString("vendor_number"));
                }
            }
            assertThat(numbers).hasSize(7);
            assertThat(numbers.get(acmeTwo))
                    .as("a literal base keeps its own number")
                    .isEqualTo("ACME-2");
            assertThat(java.util.Set.of(numbers.get(acme), numbers.get(acmeUpper)))
                    .as("the duplicate skips the suffix a literal base already holds")
                    .containsExactlyInAnyOrder("ACME", "ACME-3");
            assertThat(numbers.get(michelinA)).isIn("MICHELIN-EU", "MICHELIN-EU-2");
            assertThat(numbers.get(michelinDuplicateA)).isIn("MICHELIN-EU", "MICHELIN-EU-2");
            assertThat(numbers.get(michelinA)).isNotEqualTo(numbers.get(michelinDuplicateA));
            assertThat(numbers.get(oddRefA)).isEqualTo("V-LEGACY");
            assertThat(numbers.get(michelinB)).isEqualTo("MICHELIN-EU");

            try (ResultSet nullable = statement.executeQuery("SELECT is_nullable FROM information_schema.columns"
                    + " WHERE table_name = 'supplier_profile' AND column_name = 'vendor_id'")) {
                assertThat(nullable.next()).isTrue();
                assertThat(nullable.getString(1)).isEqualTo("NO");
            }
            try (ResultSet audit = statement.executeQuery(
                    "SELECT count(*) FROM supplier_exchange_audit WHERE vendor_profile_id = '" + michelinA + "'")) {
                audit.next();
                assertThat(audit.getInt(1)).isEqualTo(1);
            }
            try (ResultSet invoice =
                    statement.executeQuery("SELECT vendor_profile_id, vendor_id FROM supplier_invoice")) {
                assertThat(invoice.next()).isTrue();
                assertThat(invoice.getObject("vendor_profile_id")).isEqualTo(michelinA);
                assertThat(invoice.getObject("vendor_id"))
                        .as("documents fetched before the vendor master keep a null vendor")
                        .isNull();
            }
        }
    }
}
