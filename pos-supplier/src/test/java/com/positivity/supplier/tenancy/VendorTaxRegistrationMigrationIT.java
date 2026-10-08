package com.positivity.supplier.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.supplier.SupplierPostgresContainer;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.migration.VendorTaxRegistrationEncryptionMigration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.migration.Context;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * #2621 ACs 6 and 7: {@code V4} (Java, the key) encrypts every stored registration number in every tenant and
 * is idempotent; {@code V5} scrubs every queued {@code supplier.vendor.updated} row to schema version 2 and
 * leaves every other row byte-identical; a replay window over the scrubbed rows re-sends no number.
 *
 * <p>On a database of its own in the shared container, migrated to V3, seeded as a pre-#2621 deployment
 * would be, then migrated to the head. Numbers are obviously fake.
 */
@ResourceLock(SupplierPostgresContainer.RESOURCE_LOCK)
@DisplayName("V4/V5 encrypt stored vendor tax registrations and scrub the outbox (#2621 AC 6, AC 7)")
class VendorTaxRegistrationMigrationIT {

    private static final UUID TENANT_A = UUID.fromString("01900000-0000-7000-8000-00000000000a");
    private static final UUID TENANT_B = UUID.fromString("01900000-0000-7000-8000-00000000000b");
    private static final String SSN = "000-00-1234";
    private static final String BN = "000000000RT0001";
    private static final String SHORT = "FAKE-123";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    private final VendorTaxIdCipher cipher = cipher();

    private PGSimpleDataSource owner;
    private PGSimpleDataSource database;
    private String databaseName;

    private static VendorTaxIdCipher cipher() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        byte[] key = new byte[32];
        java.util.Arrays.fill(key, (byte) 0x5a);
        return new VendorTaxIdCipher(environment, Base64.getEncoder().encodeToString(key), "it", "");
    }

    @BeforeEach
    void createDatabase() throws SQLException {
        owner = (PGSimpleDataSource) SupplierPostgresContainer.ownerDataSource();
        databaseName = "supplier_v4_taxreg_" + Long.toHexString(System.nanoTime());
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
        var configuration = Flyway.configure()
                .dataSource(database)
                .locations("classpath:db/migration")
                .javaMigrations(new VendorTaxRegistrationEncryptionMigration(cipher));
        if (target != null) {
            configuration = configuration.target(target);
        }
        return configuration.load();
    }

    private static void vendor(Statement statement, UUID tenantId, UUID vendorId, String number, String registrations)
            throws SQLException {
        statement.execute("INSERT INTO supplier_vendor (tenant_id, vendor_id, vendor_number, legal_name, display_name,"
                + " tax_registrations, remit_to_version, status, created_at, updated_at, created_by, version)"
                + " VALUES ('" + tenantId + "', '" + vendorId + "', '" + number + "', 'Legal', 'Display', '"
                + registrations + "'::jsonb, 0, 'ACTIVE', now(), now(), 'seed', 0)");
    }

    private static String outbox(
            Statement statement, UUID tenantId, String eventType, String payload, boolean published, String lastError)
            throws SQLException {
        UUID id = UUID.randomUUID();
        statement.execute("INSERT INTO supplier_event_outbox (id, topic, record_key, event_type, payload, created_at,"
                + " published_at, attempts, last_error, tenant_id) VALUES ('" + id + "', 'supplier.events.v1', 'k', '"
                + eventType + "', '" + payload + "', now(), " + (published ? "now()" : "NULL") + ", 0, "
                + (lastError == null ? "NULL" : "'" + lastError + "'") + ", '" + tenantId + "')");
        return id.toString();
    }

    private static String vendorFactV1(UUID vendorId, String registrations) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"supplier.vendor.updated\","
                + "\"schemaVersion\":1,\"aggregateId\":\"" + vendorId + "\",\"aggregateVersion\":0,"
                + "\"payload\":{\"vendorId\":\"" + vendorId + "\",\"taxRegistrations\":" + registrations + "}}";
    }

    @Test
    @DisplayName("every stored number is sealed in both tenants, no number key remains, and a rerun changes nothing;"
            + " the outbox is version 2 without numbers and other rows are untouched")
    void encryptsAndScrubs() throws Exception {
        flyway("3").migrate();

        UUID vendorA = UUID.fromString("01980000-0000-7000-8000-00000000a0a1");
        UUID vendorB = UUID.fromString("01980000-0000-7000-8000-00000000b0b1");
        UUID vendorEmpty = UUID.fromString("01980000-0000-7000-8000-00000000a0a2");
        String registrationsA = "[{\"scheme\":\"SSN\",\"number\":\"" + SSN + "\",\"region\":null},"
                + "{\"scheme\":\"BN\",\"number\":\"" + SHORT + "\",\"region\":\"ON\"}]";
        String registrationsB = "[{\"scheme\":\"GST_HST\",\"number\":\"" + BN + "\",\"region\":\"ON\"}]";
        String otherPayload = "{\"eventType\":\"supplier.stock.snapshot.ready\",\"schemaVersion\":1,"
                + "\"payload\":{\"number\":\"" + SSN + "\"}}";
        String vendorPublished;
        String vendorPending;
        String other;
        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement()) {
            vendor(statement, TENANT_A, vendorA, "V-000001", registrationsA);
            vendor(statement, TENANT_B, vendorB, "V-000001", registrationsB);
            vendor(statement, TENANT_A, vendorEmpty, "V-000002", "[]");
            vendorPublished = outbox(
                    statement,
                    TENANT_A,
                    "supplier.vendor.updated",
                    vendorFactV1(vendorA, registrationsA),
                    true,
                    "send failed: " + SSN);
            vendorPending = outbox(
                    statement, TENANT_B, "supplier.vendor.updated", vendorFactV1(vendorB, registrationsB), false, null);
            // Another event type: untouched even though its payload has a "number" key.
            other = outbox(statement, TENANT_A, "supplier.stock.snapshot.ready", otherPayload, true, "kept as is");
        }
        Map<String, String> otherBefore = row(other);

        flyway(null).migrate();

        // ── AC 6: the vendor master ──────────────────────────────────────────────────
        Map<UUID, String> stored = storedRegistrations();
        assertThat(stored.values())
                .as("no number key and no clear number in any row")
                .noneMatch(text -> text.contains("\"number\"")
                        || text.contains(SSN)
                        || text.contains("000001234")
                        || text.contains(BN)
                        || text.contains(SHORT));
        assertThat(stored.get(vendorEmpty)).isEqualTo("[]");
        JsonNode a = JSON.readTree(stored.get(vendorA));
        JsonNode b = JSON.readTree(stored.get(vendorB));
        assertThat(a).hasSize(2);
        assertThat(a.get(0).path("last4").stringValue()).isEqualTo("1234");
        assertThat(a.get(1).path("last4").isNull()).as("under 8 alphanumerics").isTrue();
        assertThat(a.get(1).path("region").stringValue()).isEqualTo("ON");
        assertThat(b.get(0).path("last4").stringValue()).isEqualTo("0001");
        assertThat(open(TENANT_A, vendorA, a.get(0))).isEqualTo(SSN);
        assertThat(open(TENANT_A, vendorA, a.get(1))).isEqualTo(SHORT);
        assertThat(open(TENANT_B, vendorB, b.get(0))).isEqualTo(BN);
        for (JsonNode element : List.of(a.get(0), a.get(1), b.get(0))) {
            assertThat(UUID.fromString(element.path("registrationId").stringValue())
                            .version())
                    .isEqualTo(7);
        }

        // Idempotent: running the migration body again changes nothing.
        try (Connection connection = database.getConnection()) {
            connection.setAutoCommit(false);
            new VendorTaxRegistrationEncryptionMigration(cipher).migrate(context(connection));
            connection.commit();
        }
        assertThat(storedRegistrations()).isEqualTo(stored);

        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement();
                ResultSet history = statement.executeQuery(
                        "SELECT version, type, success FROM flyway_schema_history WHERE version IN ('4','5','6')"
                                + " ORDER BY installed_rank")) {
            List<String> applied = new ArrayList<>();
            while (history.next()) {
                applied.add(history.getString(1) + ":" + history.getString(2) + ":" + history.getBoolean(3));
            }
            assertThat(applied).containsExactly("4:JDBC:true", "5:SQL:true", "6:SQL:true");
        }

        // ── AC 7: the outbox ─────────────────────────────────────────────────────────
        for (String id : List.of(vendorPublished, vendorPending)) {
            Map<String, String> scrubbed = row(id);
            JsonNode envelope = JSON.readTree(scrubbed.get("payload"));
            assertThat(envelope.path("schemaVersion").asInt()).isEqualTo(2);
            assertThat(scrubbed.get("last_error")).isNull();
            assertThat(scrubbed.get("payload").contains("\"number\"")
                            || scrubbed.get("payload").contains(SSN)
                            || scrubbed.get("payload").contains(BN))
                    .as("number absent from the scrubbed row")
                    .isFalse();
        }
        JsonNode published = JSON.readTree(row(vendorPublished).get("payload")).path("payload");
        assertThat(published.path("taxRegistrations").get(0).path("last4").stringValue())
                .isEqualTo("1234");
        assertThat(published.path("taxRegistrations").get(1).path("last4").isNull())
                .isTrue();
        assertThat(published.path("taxRegistrations").get(1).path("region").stringValue())
                .isEqualTo("ON");
        assertThat(JSON.readTree(row(vendorPending).get("payload"))
                        .path("payload")
                        .path("taxRegistrations")
                        .get(0)
                        .path("last4")
                        .stringValue())
                .isEqualTo("0001");
        assertThat(row(vendorPublished).get("published_at"))
                .as("nothing is re-sent by the scrub")
                .isNotNull();
        assertThat(row(other)).as("another event type is byte-identical").isEqualTo(otherBefore);

        // A supplier.outbox.replay-requested window re-queues stored rows verbatim (markForReplayBetween):
        // after the scrub, what it would re-send carries no number.
        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement()) {
            statement.executeUpdate("UPDATE supplier_event_outbox SET published_at = NULL, attempts = 0,"
                    + " last_error = NULL WHERE published_at IS NOT NULL AND tenant_id = '" + TENANT_A
                    + "' AND topic = 'supplier.events.v1' AND event_type = 'supplier.vendor.updated'");
            try (ResultSet pending = statement.executeQuery("SELECT payload FROM supplier_event_outbox WHERE"
                    + " published_at IS NULL AND event_type = 'supplier.vendor.updated'")) {
                int count = 0;
                while (pending.next()) {
                    count++;
                    assertThat(pending.getString(1).contains(SSN)
                                    || pending.getString(1).contains("\"number\""))
                            .as("number absent from a replayed row")
                            .isFalse();
                }
                assertThat(count).isEqualTo(2);
            }
        }
    }

    private String open(UUID tenantId, UUID vendorId, JsonNode element) {
        return cipher.open(
                tenantId,
                vendorId,
                UUID.fromString(element.path("registrationId").stringValue()),
                element.path("numberCiphertext").stringValue());
    }

    private Map<UUID, String> storedRegistrations() throws SQLException {
        Map<UUID, String> stored = new HashMap<>();
        try (Connection connection = database.getConnection();
                Statement statement = connection.createStatement();
                ResultSet rows =
                        statement.executeQuery("SELECT vendor_id, tax_registrations::text FROM supplier_vendor")) {
            while (rows.next()) {
                stored.put(rows.getObject(1, UUID.class), rows.getString(2));
            }
        }
        return stored;
    }

    private Map<String, String> row(String id) throws SQLException {
        try (Connection connection = database.getConnection();
                PreparedStatement statement = connection.prepareStatement("SELECT payload, last_error,"
                        + " published_at::text, attempts::text FROM supplier_event_outbox WHERE id = ?::uuid")) {
            statement.setString(1, id);
            try (ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).isTrue();
                Map<String, String> values = new HashMap<>();
                values.put("payload", row.getString(1));
                values.put("last_error", row.getString(2));
                values.put("published_at", row.getString(3));
                values.put("attempts", row.getString(4));
                return values;
            }
        }
    }

    private static Context context(Connection connection) {
        return new Context() {
            @Override
            public Configuration getConfiguration() {
                return null;
            }

            @Override
            public Connection getConnection() {
                return connection;
            }
        };
    }
}
