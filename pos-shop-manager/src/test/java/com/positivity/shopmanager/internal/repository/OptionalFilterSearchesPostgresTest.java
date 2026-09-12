package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.PostgresSliceTestBase;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.entity.ShopAuditEntry;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.enums.ShopAuditEventType;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The three all-optional-filter searches of this module — the audit trail search and the two roster
 * searches — against the real PostgreSQL schema.
 *
 * <h2>What this defends</h2>
 *
 * All three are one JPQL string of {@code (:param IS NULL OR …)} clauses and are deliberately left
 * that way. PostgreSQL rejects that shape at parse time, with {@code could not determine data type
 * of parameter $n}, only when it cannot infer the placeholder's type — which is the case for a
 * temporal parameter and not for these: the audit search's six optional filters are five {@code
 * String}s and an enum, and each roster's is a {@code String}, all bound with a concrete type OID for
 * a value and for a null alike (issue #1891). The queries work, so they were not rewritten.
 *
 * <p>What this pins is that they go on working. Adding an optional filter of a type the driver
 * leaves untyped — any {@code Instant}, {@code LocalDate} or other temporal — would turn every call
 * to the audit search or a roster into a 500, and only a test that issues the statement to
 * PostgreSQL can see it. The audit search is a live example of how close that is: its mandatory
 * {@code recordedAt BETWEEN :from AND :to} window is already two {@link Instant} placeholders, and
 * they are harmless only because a comparison gives the server an operand to infer from, where an
 * {@code IS NULL} would not.
 *
 * <p>Each filter is exercised absent and supplied, because the failure is not always symmetric.
 */
@DisplayName("All-optional-filter searches on PostgreSQL (#1891)")
class OptionalFilterSearchesPostgresTest extends PostgresSliceTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-06-01T00:00:00Z");
    private static final Instant RECORDED_AT = Instant.parse("2026-05-15T12:00:00Z");

    private static final String BRAKES = "BRAKES";
    private static final String ALIGNMENT = "ALIGNMENT";

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private ShopAuditRepository auditEntries;

    @Autowired
    private MechanicRepository mechanics;

    @Autowired
    private MechanicSkillRepository mechanicSkills;

    @Autowired
    private TechnicianRepository technicians;

    @Autowired
    private ShopRepository shops;

    /**
     * An audit entry stamped inside the search window. {@code recordedAt} is a {@code @CreatedDate}
     * column, so auditing overwrites whatever the builder sets with the current instant; a test that
     * needs the entry to sit in a fixed window has to write the column behind the entity manager and
     * then re-read the row.
     */
    private ShopAuditEntry auditEntry(
            ShopAuditEventType eventType, String workorderId, String mechanicId, String locationId, String actor) {
        ShopAuditEntry saved = auditEntries.save(ShopAuditEntry.builder()
                .eventType(eventType)
                .workorderId(workorderId)
                .appointmentId("APPT-" + workorderId)
                .mechanicId(mechanicId)
                .locationId(locationId)
                .actorUserId(actor)
                .retentionYears(7)
                .build());
        entityManager.flush();
        entityManager
                .createNativeQuery("UPDATE shop_audit_entry SET recorded_at = ?1 WHERE id = ?2")
                .setParameter(1, RECORDED_AT)
                .setParameter(2, saved.getId())
                .executeUpdate();
        entityManager.clear();
        return auditEntries.findById(saved.getId()).orElseThrow();
    }

    private Mechanic mechanic(String personId, MechanicStatus status, String skillCode) {
        Mechanic mechanic = mechanics.saveAndFlush(Mechanic.builder()
                .personId(personId)
                .firstName("Given-" + personId)
                .lastName("Family-" + personId)
                .status(status)
                .build());
        if (skillCode != null) {
            mechanicSkills.saveAndFlush(MechanicSkill.builder()
                    .mechanic(mechanic)
                    .skillCode(skillCode)
                    .proficiencyLevel(3)
                    .build());
        }
        return mechanic;
    }

    @Nested
    @DisplayName("ShopAuditRepository.findByFilter — the audit trail search")
    class AuditFilterSearch {

        /**
         * {@link ShopAuditEntry} is {@code @Immutable} and carries no value equality, so the entries
         * are compared by id: a search re-reads its rows into fresh instances.
         */
        private List<UUID> idsOf(List<ShopAuditEntry> entries) {
            return entries.stream().map(ShopAuditEntry::getId).toList();
        }

        @Test
        @DisplayName("every dimension absent returns the window's entries rather than failing to parse")
        void everyDimensionAbsentReturnsTheWindow() {
            ShopAuditEntry entry = auditEntry(ShopAuditEventType.SCHEDULE_CREATED, "WO-1", "MECH-1", "LOC-1", "alice");

            assertThat(idsOf(auditEntries.findByFilter(null, null, null, null, null, null, WINDOW_START, WINDOW_END)))
                    .contains(entry.getId());
        }

        @Test
        @DisplayName("each dimension narrows on its own")
        void eachDimensionNarrowsOnItsOwn() {
            UUID wanted = auditEntry(ShopAuditEventType.SCHEDULE_CREATED, "WO-2", "MECH-2", "LOC-2", "alice")
                    .getId();
            UUID other = auditEntry(ShopAuditEventType.ASSIGNMENT_CREATED, "WO-3", "MECH-3", "LOC-3", "bob")
                    .getId();

            assertThat(idsOf(auditEntries.findByFilter("WO-2", null, null, null, null, null, WINDOW_START, WINDOW_END)))
                    .containsExactly(wanted);
            assertThat(idsOf(auditEntries.findByFilter(
                            null, "APPT-WO-2", null, null, null, null, WINDOW_START, WINDOW_END)))
                    .containsExactly(wanted);
            assertThat(idsOf(auditEntries.findByFilter(
                            null, null, "MECH-2", null, null, null, WINDOW_START, WINDOW_END)))
                    .containsExactly(wanted);
            assertThat(idsOf(
                            auditEntries.findByFilter(null, null, null, "alice", null, null, WINDOW_START, WINDOW_END)))
                    .containsExactly(wanted);
            assertThat(idsOf(auditEntries.findByFilter(
                            null,
                            null,
                            null,
                            null,
                            ShopAuditEventType.ASSIGNMENT_CREATED,
                            null,
                            WINDOW_START,
                            WINDOW_END)))
                    .containsExactly(other);
            assertThat(idsOf(
                            auditEntries.findByFilter(null, null, null, null, null, "LOC-2", WINDOW_START, WINDOW_END)))
                    .containsExactly(wanted);
        }

        @Test
        @DisplayName("the dimensions combine, and the date range still bounds the result")
        void dimensionsCombineWithinTheWindow() {
            UUID wanted = auditEntry(ShopAuditEventType.SCHEDULE_CREATED, "WO-4", "MECH-4", "LOC-4", "alice")
                    .getId();

            assertThat(idsOf(auditEntries.findByFilter(
                            "WO-4",
                            null,
                            "MECH-4",
                            "alice",
                            ShopAuditEventType.SCHEDULE_CREATED,
                            "LOC-4",
                            WINDOW_START,
                            WINDOW_END)))
                    .containsExactly(wanted);
            assertThat(auditEntries.findByFilter(
                            "WO-4", null, null, null, null, null, WINDOW_END, WINDOW_END.plusSeconds(86400)))
                    .as("an entry outside the window is not in the result")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("MechanicRepository.findRoster — the mechanic roster")
    class MechanicRoster {

        @Test
        @DisplayName("an absent skill filter returns every mechanic in the status")
        void absentSkillReturnsEveryMechanic() {
            Mechanic skilled = mechanic("PER-1", MechanicStatus.ACTIVE, BRAKES);
            Mechanic unskilled = mechanic("PER-2", MechanicStatus.ACTIVE, null);

            assertThat(mechanics
                            .findRoster(MechanicStatus.ACTIVE, null, PageRequest.of(0, 50))
                            .getContent())
                    .contains(skilled, unskilled);
        }

        @Test
        @DisplayName("a supplied skill filter narrows to those holding it")
        void suppliedSkillNarrows() {
            Mechanic skilled = mechanic("PER-3", MechanicStatus.ACTIVE, ALIGNMENT);
            Mechanic otherSkill = mechanic("PER-4", MechanicStatus.ACTIVE, BRAKES);

            assertThat(mechanics
                            .findRoster(MechanicStatus.ACTIVE, ALIGNMENT, PageRequest.of(0, 50))
                            .getContent())
                    .contains(skilled)
                    .doesNotContain(otherSkill);
        }

        @Test
        @DisplayName("the status filter keeps an inactive mechanic off the roster")
        void statusFilterExcludesInactive() {
            Mechanic inactive = mechanic("PER-5", MechanicStatus.INACTIVE, BRAKES);

            assertThat(mechanics
                            .findRoster(MechanicStatus.ACTIVE, null, PageRequest.of(0, 50))
                            .getContent())
                    .doesNotContain(inactive);
        }

        @Test
        @DisplayName("an unpaged roster returns every match rather than failing")
        void unpagedRosterReturnsEveryMatch() {
            Mechanic mechanic = mechanic("PER-6", MechanicStatus.ACTIVE, null);

            // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; the
            // count query has to carry the unpaged case through rather than throw.
            assertThat(mechanics
                            .findRoster(MechanicStatus.ACTIVE, null, Pageable.unpaged())
                            .getContent())
                    .contains(mechanic);
        }
    }

    @Nested
    @DisplayName("TechnicianRepository.findRosterByLocation — one location's technicians")
    class LocationTechnicianRoster {

        private Technician technicianAt(Shop shop, Mechanic mechanic) {
            return technicians.saveAndFlush(Technician.builder()
                    .personId(UUID.fromString(mechanic.getPersonId()))
                    .shop(shop)
                    .build());
        }

        private Shop shop() {
            return shops.saveAndFlush(Shop.builder()
                    .name("Shop " + UUID.randomUUID())
                    .timezone("UTC")
                    .build());
        }

        private Mechanic mechanicWithUuidPersonId(MechanicStatus status, String skillCode) {
            return mechanic(UUID.randomUUID().toString(), status, skillCode);
        }

        @Test
        @DisplayName("an absent skill filter returns every technician at the location")
        void absentSkillReturnsEveryTechnician() {
            Shop shop = shop();
            Technician skilled = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, BRAKES));
            Technician unskilled = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, null));

            assertThat(technicians
                            .findRosterByLocation(shop.getId(), MechanicStatus.ACTIVE, null, PageRequest.of(0, 50))
                            .getContent())
                    .containsExactlyInAnyOrder(skilled, unskilled);
        }

        @Test
        @DisplayName("a supplied skill filter narrows to those holding it")
        void suppliedSkillNarrows() {
            Shop shop = shop();
            Technician skilled = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, ALIGNMENT));
            Technician otherSkill = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, BRAKES));

            assertThat(technicians
                            .findRosterByLocation(shop.getId(), MechanicStatus.ACTIVE, ALIGNMENT, PageRequest.of(0, 50))
                            .getContent())
                    .containsExactly(skilled)
                    .doesNotContain(otherSkill);
        }

        @Test
        @DisplayName("another location's technicians are not on this roster")
        void otherLocationsAreExcluded() {
            Shop shop = shop();
            Shop elsewhere = shop();
            Technician here = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, null));
            Technician there = technicianAt(elsewhere, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, null));

            assertThat(technicians
                            .findRosterByLocation(shop.getId(), MechanicStatus.ACTIVE, null, PageRequest.of(0, 50))
                            .getContent())
                    .containsExactly(here)
                    .doesNotContain(there);
        }

        @Test
        @DisplayName("an unpaged roster returns every match rather than failing")
        void unpagedRosterReturnsEveryMatch() {
            Shop shop = shop();
            Technician technician = technicianAt(shop, mechanicWithUuidPersonId(MechanicStatus.ACTIVE, null));

            assertThat(technicians
                            .findRosterByLocation(shop.getId(), MechanicStatus.ACTIVE, null, Pageable.unpaged())
                            .getContent())
                    .containsExactly(technician);
        }

        @Test
        @DisplayName("a location with no technicians is an empty roster, not a failure")
        void emptyLocationIsAnEmptyRoster() {
            Shop shop = shop();

            assertThat(technicians
                            .findRosterByLocation(shop.getId(), MechanicStatus.ACTIVE, BRAKES, PageRequest.of(0, 50))
                            .getContent())
                    .isEmpty();
        }
    }
}
