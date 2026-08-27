package com.positivity.inventory.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs {@code V43__storage_compatibility.sql} verbatim against an in-memory H2 in PostgreSQL mode
 * (issue #1514).
 *
 * <p>The seeded matrix is the feature's specification expressed as data, so it is asserted row by
 * row against the #1514 contract table rather than merely counted: a wrong id or a dropped storage
 * class would silently reroute a whole category of goods, and Flyway is disabled under the
 * {@code test} profile so nothing else executes this file.
 */
class StorageCompatibilityMigrationTest {

    private static final String MIGRATION = "/db/migration/V43__storage_compatibility.sql";

    private static final String CATEGORY_PREFIX = "01960030-0000-7000-8000-0000000000";
    private static final String SUBCATEGORY_PREFIX = "01960031-0000-7000-8000-0000000000";

    private Connection connection;

    @BeforeEach
    void applyMigration() throws Exception {
        connection = DriverManager.getConnection(
                "jdbc:h2:mem:v43_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        for (String statement : MigrationScripts.statements(MIGRATION)) {
            MigrationScripts.execute(connection, statement);
        }
    }

    @AfterEach
    void tearDown() throws SQLException {
        connection.close();
    }

    private int rowsFor(String level, String prefix, String suffix, String storageCategoryCode) throws SQLException {
        return MigrationScripts.count(
                connection,
                "storage_compatibility",
                "match_level = '" + level + "' AND catalog_ref_id = '" + prefix + suffix
                        + "' AND storage_category_code = '" + storageCategoryCode + "'");
    }

    private int acceptedCountFor(String level, String prefix, String suffix) throws SQLException {
        return MigrationScripts.count(
                connection,
                "storage_compatibility",
                "match_level = '" + level + "' AND catalog_ref_id = '" + prefix + suffix + "'");
    }

    @Test
    @DisplayName("#1514 - the migration parses and applies on H2 in PostgreSQL mode")
    void appliesOnH2() throws SQLException {
        assertThat(MigrationScripts.count(connection, "storage_compatibility", "1 = 1"))
                .isPositive();
    }

    @Test
    @DisplayName("#1514 - all 12 catalog categories and all three subcategory overrides are seeded")
    void seedsTheWholeMatrix() throws SQLException {
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "match_level = 'CATEGORY' AND catalog_ref_id LIKE '01960030-0000-7000-8000-%'"))
                .isEqualTo(26);
        // Batteries (1) + Hydraulic Cylinders & Hoses (1) + ATF & Gear Oil (2 classes) = 4 rows.
        assertThat(MigrationScripts.count(connection, "storage_compatibility", "match_level = 'SUBCATEGORY'"))
                .isEqualTo(4);
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "match_level = 'CATEGORY' AND catalog_ref_id IN"
                                + " (SELECT DISTINCT catalog_ref_id FROM storage_compatibility"
                                + " WHERE match_level = 'CATEGORY')"))
                .isEqualTo(26);
    }

    @Test
    @DisplayName("#1514 - every category's accepted classes match the contract matrix exactly")
    void categoryMatrixMatchesTheContract() throws SQLException {
        // Cat id suffix -> the accepted storage classes, verbatim from the #1514 contract table.
        record Row(String suffix, List<String> accepted) {}
        List<Row> contract = List.of(
                new Row("01", List.of("TIRE_RACK", "BULK_FLOOR")),
                new Row("02", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("03", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("04", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("05", List.of("SMALL_PARTS_BIN", "BULK_FLOOR", "GENERAL")),
                new Row("06", List.of("SMALL_PARTS_BIN", "BULK_FLOOR", "GENERAL")),
                new Row("07", List.of("OIL_STORAGE", "BULK_FLOOR")),
                new Row("08", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("09", List.of("BULK_FLOOR", "GENERAL")),
                new Row("0a", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("0b", List.of("SMALL_PARTS_BIN", "GENERAL")),
                new Row("0c", List.of("BULK_FLOOR", "GENERAL")));

        for (Row row : contract) {
            for (String accepted : row.accepted()) {
                assertThat(rowsFor("CATEGORY", CATEGORY_PREFIX, row.suffix(), accepted))
                        .as("category %s accepts %s", row.suffix(), accepted)
                        .isEqualTo(1);
            }
            assertThat(acceptedCountFor("CATEGORY", CATEGORY_PREFIX, row.suffix()))
                    .as("category %s has no extra accepted classes", row.suffix())
                    .isEqualTo(row.accepted().size());
        }
    }

    @Test
    @DisplayName("#1514 - Batteries overrides its parent to BATTERY_RACK only, with containment")
    void batteriesOverrideRequiresContainment() throws SQLException {
        assertThat(acceptedCountFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "0e")).isEqualTo(1);
        assertThat(rowsFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "0e", "BATTERY_RACK"))
                .isEqualTo(1);
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "catalog_ref_id = '" + SUBCATEGORY_PREFIX + "0e' AND requires_containment = TRUE"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#1514 - Hydraulic Cylinders & Hoses overrides its parent to BULK_FLOOR only")
    void hydraulicsOverrideIsBulkFloorOnly() throws SQLException {
        assertThat(acceptedCountFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "27")).isEqualTo(1);
        assertThat(rowsFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "27", "BULK_FLOOR"))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("#1514 - ATF & Gear Oil reaches oil storage despite its non-fluid parent category")
    void atfOverrideReachesOilStorage() throws SQLException {
        // ATF & Gear Oil (12) is a bulk fluid filed under Drivetrain & Transmission (05), not under
        // Fluids & Chemicals (07). Its parent's accepted set is entirely uncontained and has no
        // OIL_STORAGE, so without this override a gallon of ATF is accepted into a small-parts bin
        // and refused from oil storage — the inversion of what the shop floor needs.
        assertThat(acceptedCountFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "12")).isEqualTo(2);
        assertThat(rowsFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "12", "OIL_STORAGE"))
                .isEqualTo(1);
        assertThat(rowsFor("SUBCATEGORY", SUBCATEGORY_PREFIX, "12", "BULK_FLOOR"))
                .isEqualTo(1);
        // Oil storage is the contained option; a bulk floor stays uncontained, mirroring how
        // Fluids & Chemicals is expressed, so a quart on the floor is still legal.
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "catalog_ref_id = '" + SUBCATEGORY_PREFIX + "12' AND requires_containment = TRUE"))
                .isEqualTo(1);
        // The parent category must NOT have gained OIL_STORAGE — the override is what carries it.
        assertThat(rowsFor("CATEGORY", CATEGORY_PREFIX, "05", "OIL_STORAGE")).isZero();
    }

    @Test
    @DisplayName("#1514 - containment is required exactly on the two containment-bearing classes")
    void containmentIsRequiredOnlyWhereItShouldBe() throws SQLException {
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "requires_containment = TRUE AND storage_category_code NOT IN ('BATTERY_RACK','OIL_STORAGE')"))
                .isZero();
        assertThat(MigrationScripts.count(
                        connection,
                        "storage_compatibility",
                        "requires_containment = FALSE AND storage_category_code IN ('BATTERY_RACK','OIL_STORAGE')"))
                .isZero();
    }

    @Test
    @DisplayName("#1514 - no row names STAGING or QUARANTINE: they are putaway sources")
    void sourceOnlyClassesAreNeverSeeded() throws SQLException {
        assertThat(MigrationScripts.count(
                        connection, "storage_compatibility", "storage_category_code IN ('STAGING', 'QUARANTINE')"))
                .isZero();
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint makes a STAGING row structurally impossible")
    void rejectsASourceOnlyStorageClass() {
        assertThatThrownBy(() -> insertRow("CATEGORY", CATEGORY_PREFIX + "01", "STAGING"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("STORAGE_COMPATIBILITY_STORAGE_CATEGORY_CODE_CHECK");
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint rejects a storage class outside the enum")
    void rejectsUnknownStorageClass() {
        assertThatThrownBy(() -> insertRow("CATEGORY", CATEGORY_PREFIX + "01", "TIRE_CAROUSEL"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("STORAGE_COMPATIBILITY_STORAGE_CATEGORY_CODE_CHECK");
    }

    @Test
    @DisplayName("#1514 - the CHECK constraint rejects a match level outside the enum")
    void rejectsUnknownMatchLevel() {
        assertThatThrownBy(() -> insertRow("SKU", CATEGORY_PREFIX + "01", "TIRE_RACK"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("STORAGE_COMPATIBILITY_MATCH_LEVEL_CHECK");
    }

    @Test
    @DisplayName("#1514 - the same class cannot be listed twice for one catalog reference")
    void rejectsADuplicatePair() {
        assertThatThrownBy(() -> insertRow("CATEGORY", CATEGORY_PREFIX + "01", "TIRE_RACK"))
                .isInstanceOf(SQLException.class);
    }

    private void insertRow(String matchLevel, String catalogRefId, String storageCategoryCode) throws SQLException {
        MigrationScripts.execute(
                connection,
                "INSERT INTO storage_compatibility (compatibility_id, match_level, catalog_ref_id,"
                        + " storage_category_code, requires_containment) VALUES ('" + UUID.randomUUID() + "', '"
                        + matchLevel + "', '" + catalogRefId + "', '" + storageCategoryCode + "', FALSE)");
    }
}
