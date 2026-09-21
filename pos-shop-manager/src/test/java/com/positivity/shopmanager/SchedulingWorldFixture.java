package com.positivity.shopmanager;

import com.positivity.shopmanager.internal.entity.ConflictRule;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.enums.ConflictResourceType;
import com.positivity.shopmanager.internal.enums.ConflictSeverity;
import com.positivity.shopmanager.internal.repository.ConflictRuleRepository;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The world a booking needs before {@code SchedulingConflictEvaluator} can answer about it
 * (CAP-326/CAP-329): the platform rule catalog, and somebody rostered to do the work.
 *
 * <p>Contract integration tests run on H2 with {@code spring.flyway.enabled=false} and
 * {@code ddl-auto=create-drop}, so the schema exists but none of the module's seeds do. Two of
 * those seeded facts are preconditions for a successful booking rather than optional colour:
 *
 * <ul>
 *   <li>{@code conflict_rule} — the evaluator treats a rule its code names but the catalog lacks as
 *       a deployment defect and throws, so an empty catalog turns every booking into a 500 the
 *       moment any rule wants to fire. {@link #seedConflictRules} mirrors
 *       {@code R__seed_shop_manager_1_conflict_rules.sql}, which is that table's only writer in
 *       production, rather than re-running its Postgres-flavoured SQL on H2.
 *   <li>{@code ext_people_staffing_assignment} — with no ACTIVE technician covering the booking's
 *       local date, HARD {@code MECHANIC_UNAVAILABLE} fires and the booking is refused 409
 *       (#2035 answer 5). A test asserting the accepted path must roster somebody first;
 *       {@link #rosterTechnician} is the replica row {@code people.events.v1} would have written.
 * </ul>
 *
 * <p>Both helpers converge on re-run, so a {@code @BeforeEach} may call them for every test.
 */
public final class SchedulingWorldFixture {

    /** The role and status strings {@code SkillRequirementResolver} matches a mechanic on. */
    private static final String TECHNICIAN_ROLE = "TECHNICIAN";

    private static final String ACTIVE = "ACTIVE";

    private SchedulingWorldFixture() {}

    /**
     * Seeds DECISION-SHOPMGMT-002's eight-rule catalog, codes, severities and active flags exactly
     * as the repeatable migration does — {@code MECHANIC_OVERTIME} inactive included, since a test
     * asserting nothing fires for it should be asserting the production configuration.
     */
    public static void seedConflictRules(ConflictRuleRepository conflictRuleRepository) {
        conflictRuleRepository.saveAll(conflictRules());
    }

    /**
     * The seeded catalog itself, so a unit test that stubs {@code ConflictRuleRepository} renders the
     * templates production ships rather than a paraphrase of them — the templates are the refusal a
     * caller reads (#2139, #2140), and a test with its own copy of them proves nothing about that.
     */
    public static List<ConflictRule> conflictRules() {
        return List.of(
                rule(
                        "BAY_DOUBLE_BOOKED",
                        ConflictSeverity.HARD,
                        ConflictResourceType.BAY,
                        "Bay {resource} is already booked for part of {start}–{end} {zone}.",
                        true),
                rule(
                        "MECHANIC_UNAVAILABLE",
                        ConflictSeverity.HARD,
                        ConflictResourceType.MECHANIC,
                        "No technician staffing assignment covers {start}–{end} {zone} at this location{reason}.",
                        true),
                rule(
                        "MECHANIC_OVERTIME",
                        ConflictSeverity.SOFT,
                        ConflictResourceType.MECHANIC,
                        "Booking {start}–{end} {zone} puts the assigned mechanic into overtime.",
                        false),
                rule(
                        "FACILITY_NEAR_CAPACITY",
                        ConflictSeverity.SOFT,
                        ConflictResourceType.CAPACITY,
                        "The location is near capacity for {start}–{end} {zone}.",
                        true),
                rule(
                        "COMPETENT_MECHANIC_UNAVAILABLE",
                        ConflictSeverity.SOFT,
                        ConflictResourceType.SKILL,
                        "A mechanic holding {skills} works at this location but none is free for {start}–{end} {zone}.",
                        true),
                rule(
                        "NO_COMPETENT_MECHANIC_ROSTERED",
                        ConflictSeverity.SOFT,
                        ConflictResourceType.SKILL,
                        "No mechanic at this location holds {skills}.",
                        true),
                rule(
                        "OUTSIDE_OPERATING_HOURS",
                        ConflictSeverity.HARD,
                        ConflictResourceType.HOURS,
                        "{start}–{end} {zone} falls outside the location's operating hours for that day.",
                        true),
                rule(
                        "FACILITY_CLOSED",
                        ConflictSeverity.HARD,
                        ConflictResourceType.HOURS,
                        "The location is closed on {date}{reason}.",
                        true));
    }

    /** The seeded message template for one rule code. */
    public static String messageTemplate(String code) {
        return conflictRules().stream()
                .filter(rule -> rule.getCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no seeded conflict rule " + code))
                .getMessageTemplate();
    }

    /**
     * Rosters one ACTIVE technician at {@code locationId} with open-ended effective dates, so the
     * assignment covers whatever date a test books on.
     *
     * @return the technician's person id, which is also the resource id a TECHNICIAN-lane
     *     appointment would carry
     */
    public static UUID rosterTechnician(
            ExtStaffingAssignmentReplicaRepository staffingAssignmentRepository, UUID locationId) {
        UUID personId = derivedId("person:" + locationId);
        staffingAssignmentRepository.save(ExtStaffingAssignmentReplica.builder()
                .assignmentId(derivedId("assignment:" + locationId))
                .employeeId(derivedId("employee:" + locationId))
                .personId(personId)
                .locationId(locationId)
                .role(TECHNICIAN_ROLE)
                .primary(true)
                .status(ACTIVE)
                .effectiveFrom(null)
                .effectiveTo(null)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"))
                .build());
        return personId;
    }

    private static ConflictRule rule(
            String code,
            ConflictSeverity severity,
            ConflictResourceType resourceType,
            String messageTemplate,
            boolean active) {
        return ConflictRule.builder()
                .id(derivedId("conflict_rule:" + code))
                .code(code)
                .severity(severity)
                .resourceType(resourceType)
                .messageTemplate(messageTemplate)
                .active(active)
                .build();
    }

    /**
     * The migration derives a seeded id from {@code md5(<name>)}; {@link UUID#nameUUIDFromBytes} is
     * the same digest, so an id stays stable across runs here too and a re-seed updates its row
     * instead of adding one.
     */
    private static UUID derivedId(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
