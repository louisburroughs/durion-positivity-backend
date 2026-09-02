package com.positivity.shopmanager.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
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

    private static final UUID LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");
    private static final UUID OTHER_LOCATION_ID = UUID.fromString("01960011-0000-7000-8000-000000000002");
    private static final UUID ZULU_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000011");
    private static final UUID ALPHA_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000012");
    private static final UUID INACTIVE_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000013");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000014");
    private static final UUID ALIGNMENT_PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000015");

    @Autowired
    private TechnicianRepository technicianRepository;

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
        insertTechnician(UUID.fromString("01960011-0000-7000-8000-000000000021"), ZULU_PERSON_ID, LOCATION_ID);
        insertTechnician(UUID.fromString("01960011-0000-7000-8000-000000000022"), ALPHA_PERSON_ID, LOCATION_ID);
        insertTechnician(UUID.fromString("01960011-0000-7000-8000-000000000023"), INACTIVE_PERSON_ID, LOCATION_ID);
        insertTechnician(UUID.fromString("01960011-0000-7000-8000-000000000024"), OTHER_PERSON_ID, OTHER_LOCATION_ID);
    }

    @Test
    void locationRosterFiltersBeforePagingAndOrdersByMechanicName() {
        Page<Technician> firstPage = technicianRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.ACTIVE, "BRAKES", PageRequest.of(0, 1));
        Page<Technician> secondPage = technicianRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.ACTIVE, "BRAKES", PageRequest.of(1, 1));

        assertThat(firstPage.getTotalElements()).isEqualTo(2);
        assertThat(firstPage.getContent()).extracting(Technician::getPersonId).containsExactly(ALPHA_PERSON_ID);
        assertThat(secondPage.getContent()).extracting(Technician::getPersonId).containsExactly(ZULU_PERSON_ID);
    }

    @Test
    void locationRosterHonorsExplicitStatus() {
        Page<Technician> result = technicianRepository.findRosterByLocation(
                LOCATION_ID, MechanicStatus.INACTIVE, null, PageRequest.of(0, 20));

        assertThat(result.getContent()).extracting(Technician::getPersonId).containsExactly(INACTIVE_PERSON_ID);
    }

    @Test
    void globalRosterFiltersByExactSkillBeforePaging() {
        Page<Mechanic> result = mechanicRepository.findRoster(
                MechanicStatus.ACTIVE,
                "ALIGNMENT",
                PageRequest.of(0, 20, Sort.by("lastName", "firstName", "personId")));

        assertThat(result.getTotalElements()).isEqualTo(1);
        assertThat(result.getContent())
                .extracting(Mechanic::getPersonId)
                .containsExactly(ALIGNMENT_PERSON_ID.toString());
    }

    private void insertShop(UUID id, String name) {
        jdbcTemplate.update(
                "INSERT INTO shop (id, name, created_at, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                id,
                name);
    }

    private void insertMechanic(UUID personId, String firstName, String lastName, String status, String skillCode) {
        UUID mechanicId = UUID.nameUUIDFromBytes(personId.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        jdbcTemplate.update("""
        INSERT INTO mechanic
            (mechanic_id, person_id, first_name, last_name, status, version,
             created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, mechanicId, personId.toString(), firstName, lastName, status);
        jdbcTemplate.update("""
        INSERT INTO mechanic_skill
            (id, mechanic_id, skill_code, proficiency_level, created_at, updated_at)
        VALUES (?, ?, ?, 3, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, UUID.randomUUID(), mechanicId, skillCode);
    }

    private void insertTechnician(UUID id, UUID personId, UUID locationId) {
        jdbcTemplate.update("""
        INSERT INTO technician (id, person_id, shop_id, created_at, updated_at)
        VALUES (?, ?, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
        """, id, personId, locationId);
    }
}
