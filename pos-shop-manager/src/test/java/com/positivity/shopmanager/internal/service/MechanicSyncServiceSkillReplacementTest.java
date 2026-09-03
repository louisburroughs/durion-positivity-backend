package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.service.dto.HrMechanicEvent;
import com.positivity.shopmanager.internal.service.enums.HrEventType;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-backed coverage for {@code MechanicSyncServiceImpl.replaceMechanicSkills}.
 *
 * <p>#1679 turned {@link MechanicSkillRepository#deleteAllByMechanicId} into a bulk JPQL delete.
 * The caller runs, inside one transaction, {@code mechanicRepository.save(mechanic)} then the
 * bulk delete then {@code mechanicSkillRepository.saveAll(newSkills)}. A bulk JPQL statement
 * bypasses the persistence context, so whether the pending mechanic insert is flushed before the
 * delete (and before the skill inserts whose foreign key needs the mechanic row) is a Hibernate
 * flush-ordering question that {@link MechanicSyncServiceTest} cannot answer with mocks. These
 * tests drive the real service against H2 and read the outcome back through the repositories.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MechanicSyncServiceSkillReplacementTest {

    private static final UUID NEW_PERSON_ID = UUID.fromString("01960012-0000-7000-8000-000000000011");
    private static final UUID EXISTING_PERSON_ID = UUID.fromString("01960012-0000-7000-8000-000000000012");
    private static final UUID BYSTANDER_PERSON_ID = UUID.fromString("01960012-0000-7000-8000-000000000013");
    private static final UUID UPSERT_EVENT_ID = UUID.fromString("01960012-0000-7000-8000-000000000021");
    private static final UUID SKILLS_EVENT_ID = UUID.fromString("01960012-0000-7000-8000-000000000022");
    private static final Instant OCCURRED_AT = Instant.parse("2026-01-15T09:00:00Z");
    private static final long SEEDED_VERSION = 1L;

    @Autowired
    private MechanicSyncService mechanicSyncService;

    @Autowired
    private MechanicRepository mechanicRepository;

    @Autowired
    private MechanicSkillRepository mechanicSkillRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @BeforeEach
    void setUp() {
        insertMechanic(EXISTING_PERSON_ID, "Erin", "Existing");
        insertSkill(EXISTING_PERSON_ID, "BRAKES");
        insertSkill(EXISTING_PERSON_ID, "ALIGNMENT");
        insertMechanic(BYSTANDER_PERSON_ID, "Bob", "Bystander");
        insertSkill(BYSTANDER_PERSON_ID, "TIRES");
    }

    /**
     * New mechanic: the mechanic insert is still pending in the persistence context when the bulk
     * delete runs, and the skill inserts that follow carry a foreign key to that mechanic row.
     */
    @Test
    void upsertOfNewMechanicWithSkillsPersistsMechanicBeforeItsSkills() {
        assertThat(mechanicRepository.findByPersonId(NEW_PERSON_ID.toString())).isEmpty();

        mechanicSyncService.processHrEvent(HrMechanicEvent.builder()
                .eventId(UPSERT_EVENT_ID)
                .eventType(HrEventType.MECHANIC_UPSERTED)
                .personId(NEW_PERSON_ID.toString())
                .version(SEEDED_VERSION)
                .occurredAt(OCCURRED_AT)
                .payload(HrMechanicEvent.Payload.builder()
                        .firstName("Nina")
                        .lastName("Newcomer")
                        .skills(List.of(skill("OIL_CHANGE", 3), skill("BRAKE_REPLACE", 2)))
                        .build())
                .build());

        Mechanic created =
                mechanicRepository.findByPersonId(NEW_PERSON_ID.toString()).orElseThrow();
        assertThat(created.getMechanicId()).isNotNull();
        assertThat(created.getStatus()).isEqualTo(MechanicStatus.ACTIVE);
        assertThat(created.getVersion()).isEqualTo(SEEDED_VERSION);

        List<MechanicSkill> skills = mechanicSkillRepository.findAllByMechanicIdIn(List.of(created.getMechanicId()));
        assertThat(skills)
                .extracting(
                        MechanicSkill::getMechanicId, MechanicSkill::getSkillCode, MechanicSkill::getProficiencyLevel)
                .containsExactlyInAnyOrder(
                        tuple(created.getMechanicId(), "OIL_CHANGE", 3),
                        tuple(created.getMechanicId(), "BRAKE_REPLACE", 2));
        assertThat(rowCount("mechanic_skill", created.getMechanicId())).isEqualTo(2);
    }

    /** Feed path: a newer MECHANIC_SKILLS_UPDATED event replaces the whole seeded skill set. */
    @Test
    void skillsUpdateOnExistingMechanicReplacesTheWholeSet() {
        UUID existingMechanicId = mechanicId(EXISTING_PERSON_ID);
        assertThat(rowCount("mechanic_skill", existingMechanicId)).isEqualTo(2);

        mechanicSyncService.processHrEvent(HrMechanicEvent.builder()
                .eventId(SKILLS_EVENT_ID)
                .eventType(HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(EXISTING_PERSON_ID.toString())
                .version(SEEDED_VERSION + 1)
                .occurredAt(OCCURRED_AT)
                .payload(HrMechanicEvent.Payload.builder()
                        .skills(List.of(skill("DIAGNOSTICS", 4)))
                        .build())
                .build());

        assertReplacedWithSingleSkill(existingMechanicId, "DIAGNOSTICS", 4);
        assertThat(mechanicRepository
                        .findByPersonId(EXISTING_PERSON_ID.toString())
                        .orElseThrow()
                        .getVersion())
                .isEqualTo(SEEDED_VERSION + 1);
    }

    /** Operator path: {@code replaceSkills} rides the same replace-set sequence via a synthetic event. */
    @Test
    void operatorReplaceSkillsOnExistingMechanicReplacesTheWholeSet() {
        UUID existingMechanicId = mechanicId(EXISTING_PERSON_ID);
        assertThat(rowCount("mechanic_skill", existingMechanicId)).isEqualTo(2);

        mechanicSyncService.replaceSkills(EXISTING_PERSON_ID.toString(), List.of(skill("ELECTRICAL", 5)));

        assertReplacedWithSingleSkill(existingMechanicId, "ELECTRICAL", 5);
        assertThat(mechanicRepository
                        .findByPersonId(EXISTING_PERSON_ID.toString())
                        .orElseThrow()
                        .getVersion())
                .isGreaterThan(SEEDED_VERSION);
    }

    /** Feed path: an explicit empty skill list clears the set, unlike a null list which preserves it. */
    @Test
    void emptySkillListOnExistingMechanicClearsTheWholeSet() {
        UUID existingMechanicId = mechanicId(EXISTING_PERSON_ID);
        UUID bystanderMechanicId = mechanicId(BYSTANDER_PERSON_ID);
        assertThat(rowCount("mechanic_skill", existingMechanicId)).isEqualTo(2);

        mechanicSyncService.processHrEvent(HrMechanicEvent.builder()
                .eventId(SKILLS_EVENT_ID)
                .eventType(HrEventType.MECHANIC_SKILLS_UPDATED)
                .personId(EXISTING_PERSON_ID.toString())
                .version(SEEDED_VERSION + 1)
                .occurredAt(OCCURRED_AT)
                .payload(HrMechanicEvent.Payload.builder().skills(List.of()).build())
                .build());

        assertThat(mechanicSkillRepository.findAllByMechanicIdIn(List.of(existingMechanicId, bystanderMechanicId)))
                .extracting(MechanicSkill::getMechanicId, MechanicSkill::getSkillCode)
                .containsExactly(tuple(bystanderMechanicId, "TIRES"));
        assertThat(rowCount("mechanic_skill", existingMechanicId)).isZero();
        assertThat(rowCount("mechanic_skill", bystanderMechanicId)).isEqualTo(1);
    }

    private void assertReplacedWithSingleSkill(UUID targetMechanicId, String skillCode, int proficiency) {
        UUID bystanderMechanicId = mechanicId(BYSTANDER_PERSON_ID);

        List<MechanicSkill> skills =
                mechanicSkillRepository.findAllByMechanicIdIn(List.of(targetMechanicId, bystanderMechanicId));
        assertThat(skills)
                .extracting(
                        MechanicSkill::getMechanicId, MechanicSkill::getSkillCode, MechanicSkill::getProficiencyLevel)
                .containsExactlyInAnyOrder(
                        tuple(targetMechanicId, skillCode, proficiency), tuple(bystanderMechanicId, "TIRES", 3));
        assertThat(rowCount("mechanic_skill", targetMechanicId)).isEqualTo(1);
        assertThat(rowCount("mechanic_skill", bystanderMechanicId)).isEqualTo(1);
    }

    private static HrMechanicEvent.Payload.Skill skill(String skillCode, int proficiencyLevel) {
        return HrMechanicEvent.Payload.Skill.builder()
                .skillCode(skillCode)
                .proficiencyLevel(proficiencyLevel)
                .build();
    }

    private static UUID mechanicId(UUID personId) {
        return UUID.nameUUIDFromBytes(personId.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Counts rows on the transaction-bound connection. Flushes first so the count reflects
     * everything the service queued, independent of whether a repository query happened to
     * auto-flush the persistence context earlier in the test.
     */
    private int rowCount(String table, UUID mechanicId) {
        entityManager.flush();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE mechanic_id = ?", Integer.class, mechanicId);
        return count != null ? count : 0;
    }

    private void insertMechanic(UUID personId, String firstName, String lastName) {
        jdbcTemplate.update("""
                INSERT INTO mechanic
                    (mechanic_id, person_id, first_name, last_name, status, version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, mechanicId(personId), personId.toString(), firstName, lastName, SEEDED_VERSION);
    }

    private void insertSkill(UUID personId, String skillCode) {
        jdbcTemplate.update("""
                INSERT INTO mechanic_skill
                    (id, mechanic_id, skill_code, proficiency_level, created_at, updated_at)
                VALUES (?, ?, ?, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, UUID.randomUUID(), mechanicId(personId), skillCode);
    }
}
