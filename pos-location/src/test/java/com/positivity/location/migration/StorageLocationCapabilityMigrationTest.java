package com.positivity.location.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code V8__storage_location_capability.sql} verbatim against an in-memory H2 in
 * PostgreSQL mode (issue #1514).
 *
 * <p>Two things need proving and nothing else in the module proves either. First, the migration
 * has to parse on H2: the {@code dev} profile runs H2, and the Postgres-dump
 * {@code (col)::text = ANY ((ARRAY[...])::text[])} CHECK idiom that
 * {@code V1__baseline_location_schema.sql:33} carries is a syntax error there — hence the
 * {@code IN (...)} form in V8. Second, the CHECK constraint has to actually reject an
 * undeclared capability code: the entity's {@code @Enumerated(EnumType.STRING)} only guards
 * writes that go through Hibernate, so the database-level guard is what protects against a
 * hand-written UPDATE or a seed typo, and Flyway is disabled under the {@code test} profile so
 * no Spring-context test ever exercises it.
 */
class StorageLocationCapabilityMigrationTest {

    private static final String MIGRATION = "/db/migration/V8__storage_location_capability.sql";

    /** Column subset of {@code V1__baseline_location_schema.sql:33} that V8 alters. */
    private static final String BASELINE_TABLE = """
            CREATE TABLE storage_location (
                id uuid NOT NULL,
                site_id uuid NOT NULL,
                name character varying(255) NOT NULL,
                type character varying(50) NOT NULL,
                status character varying(20) NOT NULL
            )
            """;

    private Connection connection;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:v8_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        execute(BASELINE_TABLE);
        for (String statement : migrationStatements()) {
            execute(statement);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    @Test
    @DisplayName("#1514 - every StorageCategory code the contract defines is accepted")
    void acceptsEveryDeclaredCapability() throws SQLException {
        List<String> codes = List.of(
                "TIRE_RACK",
                "OIL_STORAGE",
                "BATTERY_RACK",
                "SMALL_PARTS_BIN",
                "BULK_FLOOR",
                "STAGING",
                "QUARANTINE",
                "GENERAL");

        for (String code : codes) {
            insertStorageLocation("Loc " + code, code);
        }

        assertThat(countWhere("storage_category_code IS NOT NULL")).isEqualTo(codes.size());
    }

    @Test
    @DisplayName("#1514 - a NULL capability is accepted (pre-migration rows resolve as GENERAL at read time)")
    void acceptsNullCapability() throws SQLException {
        insertStorageLocation("Undeclared", null);

        assertThat(countWhere("storage_category_code IS NULL")).isEqualTo(1);
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint rejects a capability code outside the enum")
    void rejectsUnknownCapability() {
        assertThatThrownBy(() -> insertStorageLocation("Bogus", "TIRE_CAROUSEL"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("STORAGE_LOCATION_STORAGE_CATEGORY_CODE_CHECK");
    }

    @Test
    @DisplayName("#1514 - containment and mixing policy default to FALSE/MIXED for rows that omit them")
    void appliesContainmentAndMixingDefaults() throws SQLException {
        insertStorageLocation("Defaulted", null);

        assertThat(countWhere("hazard_containment = FALSE AND allow_new_product = 'MIXED'"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint rejects a mixing policy outside the enum")
    void rejectsUnknownMixingPolicy() {
        assertThatThrownBy(() -> execute("INSERT INTO storage_location"
                        + " (id, site_id, name, type, status, allow_new_product) VALUES ('"
                        + UUID.randomUUID() + "', '" + UUID.randomUUID()
                        + "', 'Bad policy', 'BIN', 'ACTIVE', 'ANYTHING')"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("STORAGE_LOCATION_ALLOW_NEW_PRODUCT_CHECK");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertStorageLocation(String name, String categoryCode) throws SQLException {
        execute("INSERT INTO storage_location (id, site_id, name, type, status, storage_category_code) VALUES ('"
                + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', '" + name + "', 'BIN', 'ACTIVE', "
                + (categoryCode == null ? "NULL" : "'" + categoryCode + "'") + ")");
    }

    private int countWhere(String predicate) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM storage_location WHERE " + predicate)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /**
     * Splits the migration resource into executable statements: {@code --} comment lines are
     * dropped and the remainder is split on {@code ;}. The migration deliberately contains no
     * semicolon inside a literal, so a plain split is exact.
     */
    private List<String> migrationStatements() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MIGRATION)) {
            if (in == null) {
                throw new IOException("Missing migration resource " + MIGRATION);
            }
            String script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            String withoutComments = script.lines()
                    .filter(line -> !line.stripLeading().startsWith("--"))
                    .reduce("", (a, b) -> a + "\n" + b);
            return Arrays.stream(withoutComments.split(";"))
                    .map(String::trim)
                    .filter(statement -> !statement.isEmpty())
                    .toList();
        }
    }
}
