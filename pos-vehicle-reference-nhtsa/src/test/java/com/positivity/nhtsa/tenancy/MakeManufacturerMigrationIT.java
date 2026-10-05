package com.positivity.nhtsa.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * V3 (#2471) on real rows: migrate to V2, load a pre-V3 catalog in which two makes without a vPIC id share a name
 * under different manufacturers (with colliding model and vehicle-type rows, and NULL-named makes), migrate to V3
 * and check that no pair and no child is lost. Requires Docker.
 */
@DisplayName("V3 make_manufacturer migration on existing rows (#2471)")
class MakeManufacturerMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID M1 = id(1);
    private static final UUID M2 = id(2);
    private static final UUID VPIC_MAKE = id(10);
    private static final UUID L1 = id(11); // lowest id of the two "Camry" rows without a vPIC id: the survivor
    private static final UUID L2 = id(12);
    private static final UUID NULL_NAME_1 = id(13);
    private static final UUID NULL_NAME_2 = id(14);
    private static final UUID L1_COROLLA = id(21);
    private static final UUID L1_ONLY = id(22);
    private static final UUID L2_COROLLA = id(23); // same name as L1_COROLLA, different case
    private static final UUID L2_ONLY = id(24);
    private static final UUID L1_SEDAN = id(31);
    private static final UUID L2_SEDAN = id(32);
    private static final UUID L2_COUPE = id(33);

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateToV2LoadRowsThenToV3() {
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("2")
                .load()
                .migrate();

        manufacturer(M1, "Toyota");
        manufacturer(M2, "Lexus");
        jdbc.update(
                "INSERT INTO make (id, name, manufacturer_id, nhtsa_id) VALUES (?, 'Toyota', ?, 441)", VPIC_MAKE, M1);
        make(L1, "Camry", M1);
        make(L2, "CAMRY", M2);
        make(NULL_NAME_1, null, M1);
        make(NULL_NAME_2, null, M2);
        model(L1_COROLLA, "Corolla", L1);
        model(L1_ONLY, "Avalon", L1);
        model(L2_COROLLA, "COROLLA", L2);
        model(L2_ONLY, "Solara", L2);
        vehicleType(L1_SEDAN, "Sedan", L1);
        vehicleType(L2_SEDAN, "SEDAN", L2);
        vehicleType(L2_COUPE, "Coupe", L2);

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void everyExistingPairIsCopied_andTheFoldedMakeKeepsBothManufacturers() {
        assertThat(pairs(VPIC_MAKE)).containsExactly(M1);
        assertThat(pairs(L1)).containsExactlyInAnyOrder(M1, M2);
        assertThat(pairs(NULL_NAME_1)).containsExactly(M1);
        assertThat(pairs(NULL_NAME_2)).containsExactly(M2);
    }

    @Test
    void collidingLocalMakesFoldIntoTheLowestId_andNullNamesAreNotFolded() {
        assertThat(jdbc.queryForList("SELECT id FROM make WHERE lower(name) = 'camry'", UUID.class))
                .containsExactly(L1);
        assertThat(jdbc.queryForList("SELECT id FROM make WHERE name IS NULL", UUID.class))
                .containsExactlyInAnyOrder(NULL_NAME_1, NULL_NAME_2);
        assertThat(jdbc.queryForList("SELECT id FROM make WHERE nhtsa_id = 441", UUID.class))
                .containsExactly(VPIC_MAKE);
    }

    @Test
    void childrenAreMergedByNameThenMovedToTheSurvivor_nothingDistinctIsLost() {
        assertThat(jdbc.queryForList("SELECT id FROM model WHERE make_id = ?", UUID.class, L1))
                .containsExactlyInAnyOrder(L1_COROLLA, L1_ONLY, L2_ONLY);
        assertThat(jdbc.queryForList("SELECT id FROM model", UUID.class)).doesNotContain(L2_COROLLA);
        assertThat(jdbc.queryForList("SELECT id FROM vehicle_type WHERE make_id = ?", UUID.class, L1))
                .containsExactlyInAnyOrder(L1_SEDAN, L2_COUPE);
        assertThat(jdbc.queryForList("SELECT id FROM vehicle_type", UUID.class)).doesNotContain(L2_SEDAN);
    }

    @Test
    void singleManufacturerColumnAndIndexesAreGone_andUniqueIndexesHold() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'make' AND column_name = 'manufacturer_id'",
                        Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE indexname IN"
                                + " ('ux_make_nhtsa_id', 'ux_make_name_lower_no_nhtsa_id',"
                                + " 'idx_make_manufacturer_manufacturer_id')",
                        Integer.class))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM manufacturer WHERE makes_refreshed_at IS NULL", Integer.class))
                .isEqualTo(2);
    }

    private static List<UUID> pairs(UUID makeId) {
        return jdbc.queryForList("SELECT manufacturer_id FROM make_manufacturer WHERE make_id = ?", UUID.class, makeId);
    }

    private static void manufacturer(UUID id, String name) {
        jdbc.update("INSERT INTO manufacturer (id, name) VALUES (?, ?)", id, name);
    }

    private static void make(UUID id, String name, UUID manufacturerId) {
        jdbc.update("INSERT INTO make (id, name, manufacturer_id) VALUES (?, ?, ?)", id, name, manufacturerId);
    }

    private static void model(UUID id, String name, UUID makeId) {
        jdbc.update("INSERT INTO model (id, name, make_id) VALUES (?, ?, ?)", id, name, makeId);
    }

    private static void vehicleType(UUID id, String name, UUID makeId) {
        jdbc.update("INSERT INTO vehicle_type (id, vehicle_type_name, make_id) VALUES (?, ?, ?)", id, name, makeId);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", n));
    }
}
