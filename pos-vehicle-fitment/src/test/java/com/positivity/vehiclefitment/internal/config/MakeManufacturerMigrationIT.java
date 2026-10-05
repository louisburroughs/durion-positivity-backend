package com.positivity.vehiclefitment.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Map;
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
 * V4 (#2453) on real rows: migrate to V3, load a pre-V4 catalog in which two local makes share a name under
 * different manufacturers (with colliding model and vehicle-type names, each referenced by part fitments),
 * migrate to V4 and check that nothing a fitment points at was lost. Requires Docker.
 */
@DisplayName("V4 make_manufacturer migration on existing rows (#2453)")
class MakeManufacturerMigrationIT {

    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final UUID M1 = id(1);
    private static final UUID M2 = id(2);
    private static final UUID VPIC_MAKE = id(10);
    private static final UUID L1 = id(11); // lowest id of the two local "Camry" rows: the survivor
    private static final UUID L2 = id(12);
    private static final UUID L1_COROLLA = id(21);
    private static final UUID L1_ONLY = id(22);
    private static final UUID L2_COROLLA = id(23); // same name as L1_COROLLA, different case
    private static final UUID L2_ONLY = id(24);
    private static final UUID L1_SEDAN = id(31);
    private static final UUID L2_SEDAN = id(32);
    private static final UUID L2_COUPE = id(33);

    private static JdbcTemplate jdbc;
    private static Flyway flyway;

    @BeforeAll
    static void migrateToV3AndLoadRows() {
        POSTGRES.start();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("3")
                .load();
        flyway.migrate();

        manufacturer(M1, "Toyota");
        manufacturer(M2, "Lexus");
        jdbc.update(
                "INSERT INTO make (id, name, nhtsa_id, manufacturer_id, created_at, updated_at)"
                        + " VALUES (?, 'VPIC', 482, ?, now(), now())",
                VPIC_MAKE,
                M1);
        localMake(L1, "Camry", M1);
        localMake(L2, "camry", M2);
        model(L1_COROLLA, "Corolla", L1);
        model(L1_ONLY, "Only1", L1);
        model(L2_COROLLA, "corolla", L2);
        model(L2_ONLY, "Only2", L2);
        vehicleType(L1_SEDAN, "Sedan", L1);
        vehicleType(L2_SEDAN, "sedan", L2);
        vehicleType(L2_COUPE, "Coupe", L2);

        // unnamed rows must be moved, never merged with each other
        model(id(25), null, L1);
        model(id(26), null, L2);
        model(id(27), null, L2);
        vehicleType(id(34), null, L1);
        vehicleType(id(35), null, L2);
        vehicleType(id(36), null, L2);
        fitment(id(47), M2, L2, id(26), id(35));

        fitment(id(41), M1, L1, L1_COROLLA, L1_SEDAN);
        fitment(id(42), M2, L2, L2_COROLLA, L2_SEDAN);
        fitment(id(43), M2, L2, L2_ONLY, L2_COUPE);
        fitment(id(44), M1, VPIC_MAKE, null, null);
        fitment(id(45), M1, L2, null, null); // a pair only the fitment holds: L2 was recorded under M2
        fitment(id(46), null, L2, null, null); // no manufacturer: not covered by the composite FK

        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        flyway.migrate();
    }

    @AfterAll
    static void stop() {
        POSTGRES.stop();
    }

    @Test
    void everyPairIsCopiedAndLocalDuplicatesAreFoldedIntoTheLowestId() {
        assertThat(jdbc.queryForList("SELECT id FROM make ORDER BY id", UUID.class))
                .containsExactly(VPIC_MAKE, L1);
        assertThat(jdbc.queryForList("SELECT make_id, manufacturer_id FROM make_manufacturer"))
                .extracting(r -> tuple(r.get("make_id"), r.get("manufacturer_id")))
                .containsExactlyInAnyOrder(
                        tuple(VPIC_MAKE, M1), // the make's own manufacturer
                        tuple(L1, M1),
                        tuple(L1, M2)); // L2's manufacturer, and the pair fitment 45 holds
    }

    @Test
    void collidingModelsAndVehicleTypesAreMergedAndNoFitmentIsLost() {
        assertThat(jdbc.queryForList("SELECT id FROM model WHERE name IS NOT NULL ORDER BY id", UUID.class))
                .containsExactly(L1_COROLLA, L1_ONLY, L2_ONLY);
        assertThat(jdbc.queryForList(
                        "SELECT id FROM model WHERE make_id = ? AND name IS NOT NULL ORDER BY id", UUID.class, L1))
                .containsExactly(L1_COROLLA, L1_ONLY, L2_ONLY);
        assertThat(jdbc.queryForList(
                        "SELECT id FROM vehicle_type WHERE vehicle_type_name IS NOT NULL ORDER BY id", UUID.class))
                .containsExactly(L1_SEDAN, L2_COUPE);
        assertThat(jdbc.queryForList("SELECT DISTINCT make_id FROM vehicle_type", UUID.class))
                .containsExactly(L1);

        assertThat(jdbc.queryForList("SELECT make_id FROM model WHERE name IS NULL", UUID.class))
                .containsExactly(L1, L1, L1);
        assertThat(jdbc.queryForList("SELECT make_id FROM vehicle_type WHERE vehicle_type_name IS NULL", UUID.class))
                .containsExactly(L1, L1, L1);
        assertThat(byIdFitment(id(47)))
                .containsEntry("vehicle_model_id", id(26))
                .containsEntry("vehicle_type_id", id(35));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM part_fitment_entity", Integer.class))
                .isEqualTo(7);
        Map<UUID, Map<String, Object>> byId = new java.util.HashMap<>();
        jdbc.queryForList("SELECT * FROM part_fitment_entity").forEach(r -> byId.put((UUID) r.get("id"), r));
        assertThat(byId.get(id(41))).containsEntry("vehicle_make_id", L1).containsEntry("vehicle_model_id", L1_COROLLA);
        assertThat(byId.get(id(42)))
                .containsEntry("vehicle_make_id", L1)
                .containsEntry("vehicle_model_id", L1_COROLLA)
                .containsEntry("vehicle_type_id", L1_SEDAN);
        assertThat(byId.get(id(43)))
                .containsEntry("vehicle_make_id", L1)
                .containsEntry("vehicle_model_id", L2_ONLY)
                .containsEntry("vehicle_type_id", L2_COUPE);
        assertThat(byId.get(id(44))).containsEntry("vehicle_make_id", VPIC_MAKE);
        assertThat(byId.get(id(45))).containsEntry("vehicle_make_id", L1).containsEntry("vehicle_manufacturer_id", M1);
        assertThat(byId.get(id(46))).containsEntry("vehicle_make_id", L1);
        // no fitment references a row that is gone (the foreign keys also enforce this)
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM part_fitment_entity p WHERE p.vehicle_model_id IS NOT NULL"
                                + " AND NOT EXISTS (SELECT 1 FROM model m WHERE m.id = p.vehicle_model_id)",
                        Integer.class))
                .isZero();
    }

    @Test
    void oldColumnIsGoneAndNewUniquenessHolds() {
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM information_schema.columns"
                                + " WHERE table_name = 'make' AND column_name = 'manufacturer_id'",
                        Integer.class))
                .isZero();
        List<String> indexes =
                jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE tablename = 'make'", String.class);
        assertThat(indexes).contains("ux_make_nhtsa_id", "ux_make_name_lower_no_nhtsa_id");
    }

    private static Map<String, Object> byIdFitment(UUID id) {
        return jdbc.queryForMap("SELECT * FROM part_fitment_entity WHERE id = ?", id);
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-7000-8000-%012d", n));
    }

    private static void manufacturer(UUID id, String name) {
        jdbc.update(
                "INSERT INTO manufacturer (id, name, created_at, updated_at) VALUES (?, ?, now(), now())", id, name);
    }

    private static void localMake(UUID id, String name, UUID manufacturer) {
        jdbc.update(
                "INSERT INTO make (id, name, manufacturer_id, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
                id,
                name,
                manufacturer);
    }

    private static void model(UUID id, String name, UUID make) {
        jdbc.update(
                "INSERT INTO model (id, name, make_id, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
                id,
                name,
                make);
    }

    private static void vehicleType(UUID id, String name, UUID make) {
        jdbc.update(
                "INSERT INTO vehicle_type (id, vehicle_type_name, make_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, now(), now())",
                id,
                name,
                make);
    }

    private static void fitment(UUID id, UUID manufacturer, UUID make, UUID model, UUID type) {
        jdbc.update(
                "INSERT INTO part_fitment_entity (id, part_number_id, vehicle_manufacturer_id, vehicle_make_id,"
                        + " vehicle_model_id, vehicle_type_id, created_at, updated_at)"
                        + " VALUES (?, 1, ?, ?, ?, ?, now(), now())",
                id,
                manufacturer,
                make,
                model,
                type);
    }
}
