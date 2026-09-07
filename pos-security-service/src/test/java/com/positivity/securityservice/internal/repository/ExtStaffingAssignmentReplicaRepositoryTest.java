package com.positivity.securityservice.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.securityservice.internal.entity.ExtStaffingAssignmentReplica;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Effective-at predicate of the staffing-assignment read model against H2 (ADR-0061 §1/§4,
 * #1867): {@code status = ACTIVE}, {@code effectiveFrom <= asOf}, {@code effectiveTo} null or
 * {@code >= asOf}; ended rows are retained but excluded.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
@DisplayName("ExtStaffingAssignmentReplicaRepository — effective-at query")
class ExtStaffingAssignmentReplicaRepositoryTest {

    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f1");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("00000000-0000-7000-8000-0000000000f2");
    private static final LocalDate AS_OF = LocalDate.of(2026, 9, 7);

    @Autowired
    private ExtStaffingAssignmentReplicaRepository repository;

    private int seq;

    @BeforeEach
    void clear() {
        repository.deleteAll();
        seq = 0;
    }

    private ExtStaffingAssignmentReplica save(
            UUID personId, String status, LocalDate effectiveFrom, LocalDate effectiveTo) {
        seq++;
        return repository.save(ExtStaffingAssignmentReplica.builder()
                .assignmentId(UUID.fromString(String.format("00000000-0000-7000-8000-%012x", seq)))
                .personId(personId)
                .locationId(UUID.fromString(String.format("00000000-0000-7000-8000-0000ff%06x", seq)))
                .primary(false)
                .status(status)
                .effectiveFrom(effectiveFrom)
                .effectiveTo(effectiveTo)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-09-07T00:00:00Z"))
                .build());
    }

    @Test
    @DisplayName("returns only ACTIVE rows whose date range covers asOf (bounds inclusive)")
    void respectsBothDatesAndStatus() {
        var openEnded = save(PERSON_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null);
        var startsToday = save(PERSON_ID, "ACTIVE", AS_OF, null);
        var endsToday = save(PERSON_ID, "ACTIVE", LocalDate.of(2026, 1, 1), AS_OF);
        save(PERSON_ID, "ACTIVE", AS_OF.plusDays(1), null); // future
        save(PERSON_ID, "ACTIVE", LocalDate.of(2026, 1, 1), AS_OF.minusDays(1)); // expired
        save(PERSON_ID, "ENDED", LocalDate.of(2026, 1, 1), null); // ended, still stored
        save(PERSON_ID, "ACTIVE", null, null); // malformed: no start → fails closed
        save(OTHER_PERSON_ID, "ACTIVE", LocalDate.of(2026, 1, 1), null); // someone else

        var effective = repository.findActiveEffectiveOn(PERSON_ID, AS_OF);

        assertThat(effective)
                .extracting(ExtStaffingAssignmentReplica::getAssignmentId)
                .containsExactlyInAnyOrder(
                        openEnded.getAssignmentId(), startsToday.getAssignmentId(), endsToday.getAssignmentId());
        // ENDED rows are kept, not deleted.
        assertThat(repository.count()).isEqualTo(8);
    }

    @Test
    @DisplayName("the same rows answer differently on a different asOf")
    void asOfMoves() {
        save(PERSON_ID, "ACTIVE", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        save(PERSON_ID, "ACTIVE", LocalDate.of(2026, 7, 1), null);

        assertThat(repository.findActiveEffectiveOn(PERSON_ID, LocalDate.of(2026, 3, 1)))
                .extracting(ExtStaffingAssignmentReplica::getEffectiveTo)
                .containsExactly(LocalDate.of(2026, 6, 30));
        assertThat(repository.findActiveEffectiveOn(PERSON_ID, LocalDate.of(2026, 8, 1)))
                .extracting(ExtStaffingAssignmentReplica::getEffectiveTo)
                .containsExactly((LocalDate) null);
    }
}
