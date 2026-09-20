package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MechanicRosterRepositoryTest {

    private static final LocalDate ON_DATE = LocalDate.of(2026, 9, 16);
    /** Far enough past {@link #ON_DATE} that a credential stamped with it never expires here. */
    private static final LocalDate FAR_FUTURE = LocalDate.of(2030, 1, 1);

    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000002");
    private static final UUID ZULU_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000011");
    private static final UUID ALPHA_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000012");
    private static final UUID INACTIVE_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000013");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000014");
    private static final UUID ALIGNMENT_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000015");

    @Autowired
    private MechanicRepository mechanicRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        insertShop(LOCATION_ID, "Main");
        insertShop(OTHER_LOCATION_ID, "Other");
        insertMechanic(ZULU_PERSON_ID, "Zoe", "Zulu", "ACTIVE", "BRAKES");
        insertMechanic(ALPHA_PERSON_ID, "Amy", "Alpha", "ACTIVE", "BRAKES");
        insertMechanic(INACTIVE_PERSON_ID, "Ian", "Inactive", "INACTIVE", "BRAKES");
        insertMechanic(OTHER_PERSON_ID, "Oscar", "Other", "ACTIVE", "BRAKES");
        insertMechanic(ALIGNMENT_PERSON_ID, "Alice", "Alignment", "ACTIVE", "ALIGNMENT");
        insertStaffingAssignment(UUID.fromString("01960011-0000-7000-8000-000000000021"), ZULU_PERSON_ID, LOCATION_ID);
        insertStaffingAssignment(UUID.fromString("01960011-0000-7000-8000-000000000022"), ALPHA_PERSON_ID, LOCATION_ID);
        insertStaffingAssignment(
                UUID.fromString("01960011-0000-7000-8000-000000000023"), INACTIVE_PERSON_ID, LOCATION_ID);
        insertStaffingAssignment(
                UUID.fromString("01960011-0000-7000-8000-000000000024"), OTHER_PERSON_ID, OTHER_LOCATION_ID);
    }

    @Test
    void locationRosterFiltersBeforePagingAndOrdersByMechanicName() {
        Page<Mechanic> firstPage = mechanicRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.ACTIVE, "BRAKES", ON_DATE, PageRequest.of(0, 1));
        Page<Mechanic> secondPage = mechanicRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.ACTIVE, "BRAKES", ON_DATE, PageRequest.of(1, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(Mechanic::getPersonId).containsExactly(ALPHA_PERSON_ID);
        assertThat(secondPage.getContent()).extracting(Mechanic::getPersonId).containsExactly(ZULU_PERSON_ID);
    }

    @Test
    void locationRosterHonorsExplicitStatus() {
        Page<Mechanic> result = mechanicRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.INACTIVE, null, ON_DATE, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(Mechanic::getPersonId).containsExactly(INACTIVE_PERSON_ID);
    }

    @Test
    void globalRosterFiltersByExactSkillBeforePaging() {
        Page<Mechanic> result = mechanicRepository.findRoster(
                MechanicStatus.ACTIVE,
                "ALIGNMENT",
                ON_DATE,
                PageRequest.of(0, 20, Sort.by("lastName", "firstName", "personId")));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent()).extracting(Mechanic::getPersonId).containsExactly(ALIGNMENT_PERSON_ID);
    }

    /**
     * An expired credential does not count as held: the roster filter checks {@code expiresOn}
     * against the requested date, so a code that lapsed before {@link #ON_DATE} must not surface its
     * holder, while an unexpired credential for the same code still does.
     */
    @Test
    void expiredCredentialIsExcludedButAnUnexpiredOneWithTheSameCodeIsIncluded() {
        UUID expiredOnlyPersonId = UUID.fromString("01960011-0000-7000-8000-000000000017");
        insertMechanicRow(expiredOnlyPersonId, "Eve", "Expired", "ACTIVE");
        insertCredential(expiredOnlyPersonId, "BRAKES", "ACTIVE", LocalDate.of(2025, 6, 30));

        Page<Mechanic> result =
                mechanicRepository.findRoster(MechanicStatus.ACTIVE, "BRAKES", ON_DATE, PageRequest.of(0, 20));

        // The lapsed credential does not grant the skill...
        assertThat(result.getContent()).extracting(Mechanic::getPersonId).doesNotContain(expiredOnlyPersonId);
        // ...while ZULU/ALPHA's unexpired BRAKES credentials from setUp still do.
        assertThat(result.getContent()).extracting(Mechanic::getPersonId).contains(ZULU_PERSON_ID, ALPHA_PERSON_ID);
    }

    /** A REVOKED credential never counts as held, regardless of its expiry date. */
    @Test
    void revokedCredentialIsExcludedFromTheSkillFilter() {
        UUID revokedOnlyPersonId = UUID.fromString("01960011-0000-7000-8000-000000000016");
        insertMechanicRow(revokedOnlyPersonId, "Rita", "Revoked", "ACTIVE");
        insertCredential(revokedOnlyPersonId, "BRAKES", "REVOKED", FAR_FUTURE);

        Page<Mechanic> result =
                mechanicRepository.findRoster(MechanicStatus.ACTIVE, "BRAKES", ON_DATE, PageRequest.of(0, 20));

        // The revoked-only credential must not surface its holder...
        assertThat(result.getContent()).extracting(Mechanic::getPersonId).doesNotContain(revokedOnlyPersonId);
        // ...while ZULU/ALPHA's unrevoked BRAKES credentials from setUp still do, proving the
        // assertion above isn't vacuously true against an empty roster.
        assertThat(result.getContent()).extracting(Mechanic::getPersonId).contains(ZULU_PERSON_ID, ALPHA_PERSON_ID);
    }

    private void insertShop(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO shop (tenant_id, id, name, created_at, updated_at) VALUES ('01900000-0000-7000-8000-000000000001', ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                name);
    }

    private void insertMechanic(UUID personId, String firstName, String lastName, String status, String skillCode) {
        insertMechanicRow(personId, firstName, lastName, status);
        insertCredential(personId, skillCode, "ACTIVE", FAR_FUTURE);
    }

    private void insertMechanicRow(UUID personId, String firstName, String lastName, String status) {
        jdbcTemplate.update("""
        INSERT INTO mechanic
            (tenant_id, mechanic_id, person_id, first_name, last_name, status, version,
             created_at, updated_at)
        VALUES ('01900000-0000-7000-8000-000000000001', ?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, mechanicId(personId), personId, firstName, lastName, status);
    }

    private void insertCredential(UUID personId, String skillCode, String status, LocalDate expiresOn) {
        jdbcTemplate.update("""
        INSERT INTO ext_person_credential
            (tenant_id, credential_id, person_id, skill_id, skill_code, competence_code,
             min_gvwr_class, max_gvwr_class, issuer, issued_on, expires_on, status,
             aggregate_version, updated_at)
        VALUES ('01900000-0000-7000-8000-000000000001', ?, ?, ?, ?, 'TEST', 1, 8, 'TEST',
                CURRENT_DATE, ?, ?, 1, CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), personId, UUID.randomUUID(), skillCode, expiresOn, status);
    }

    private void insertStaffingAssignment(UUID assignmentId, UUID personId, UUID locationId) {
        jdbcTemplate.update("""
        INSERT INTO ext_people_staffing_assignment
            (tenant_id, assignment_id, employee_id, person_id, location_id, role, is_primary,
             status, aggregate_version, updated_at)
        VALUES ('01900000-0000-7000-8000-000000000001', ?, ?, ?, ?, 'TECHNICIAN', true, 'ACTIVE', 1,
                CURRENT_TIMESTAMP)
        """, assignmentId, UUID.randomUUID(), personId, locationId);
    }

    private static UUID mechanicId(UUID personId) {
        return UUID.nameUUIDFromBytes(personId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
