package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.enums.ResourceType;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

/**
 * {@link WorkorderRepository#findOpenResourceHoldersAtLocation} against a real database (#1656
 * review finding 3).
 *
 * <p>The dispatch board's occupancy answer is now this query's answer, and the two rules it encodes
 * are exactly the ones a mock cannot check: that {@code Workorder.isLocked()} — CANCELLED, or
 * COMPLETED and not reopened — survives translation into JPQL, and that the date bound admits a job
 * scheduled earlier (still in the bay) while excluding one scheduled later (booked, not occupying).
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false"})
class WorkorderOpenResourceHolderQueryTest {

    private static final UUID LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID OTHER_LOCATION = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID BAY = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 10);
    private static final Instant SEEDED_AT = Instant.parse("2026-03-01T00:00:00Z");

    @Autowired
    private WorkorderRepository workorderRepository;

    @Test
    @DisplayName("#1656: a work-in-progress job scheduled earlier is still holding its resource today")
    void multiDayJobScheduledEarlierIsReturned() {
        UUID id = seed(WorkorderStatus.WORK_IN_PROGRESS, TODAY.minusDays(2), false);

        assertThat(findHolders()).extracting(Workorder::getId).containsExactly(id);
    }

    @Test
    @DisplayName("#1656: unscheduled work that holds a resource counts as occupying it now")
    void nullScheduledDateIsReturned() {
        UUID id = seed(WorkorderStatus.ASSIGNED, null, false);

        assertThat(findHolders()).extracting(Workorder::getId).containsExactly(id);
    }

    @Test
    @DisplayName("#1656: work scheduled after the requested date is booked, not occupying")
    void futureScheduledWorkIsExcluded() {
        seed(WorkorderStatus.ASSIGNED, TODAY.plusDays(1), false);

        // Without this bound the fix would trade one false claim for another: tomorrow's booking
        // would black out a bay that is empty all of today.
        assertThat(findHolders()).isEmpty();
    }

    @Test
    @DisplayName("#1656: CANCELLED and COMPLETED-not-reopened releases the resource; a reopened one keeps it")
    void lockedWorkordersAreExcludedButReopenedOnesAreNot() {
        seed(WorkorderStatus.CANCELLED, TODAY, false);
        seed(WorkorderStatus.COMPLETED, TODAY, false);
        UUID reopened = seed(WorkorderStatus.COMPLETED, TODAY, true);

        // Reopening never changes the status, so a plain NOT IN (COMPLETED, CANCELLED) would free a
        // bay somebody is still working in — the JPQL has to mirror isLocked(), not approximate it.
        assertThat(findHolders()).extracting(Workorder::getId).containsExactly(reopened);
    }

    @Test
    @DisplayName("#1656: rows at another location, or holding no resource at all, are not returned")
    void otherLocationsAndUnassignedRowsAreExcluded() {
        Workorder elsewhere = new Workorder();
        stampTimestamps(elsewhere);
        elsewhere.setLocationId(OTHER_LOCATION);
        elsewhere.setResourceId(BAY);
        elsewhere.setResourceType(ResourceType.BAY);
        elsewhere.setStatus(WorkorderStatus.WORK_IN_PROGRESS);
        elsewhere.setScheduledDate(TODAY);
        workorderRepository.saveAndFlush(elsewhere);

        Workorder unassigned = new Workorder();
        stampTimestamps(unassigned);
        unassigned.setLocationId(LOCATION);
        unassigned.setStatus(WorkorderStatus.APPROVED);
        unassigned.setScheduledDate(TODAY);
        workorderRepository.saveAndFlush(unassigned);

        assertThat(findHolders()).isEmpty();
    }

    private List<Workorder> findHolders() {
        return workorderRepository.findOpenResourceHoldersAtLocation(LOCATION, TODAY);
    }

    /**
     * {@code createdAt}/{@code updatedAt} are {@code @CreatedDate}-managed, and JPA auditing is not
     * reliably on this slice's context, so they are stamped explicitly rather than left to it.
     */
    private static void stampTimestamps(Workorder workorder) {
        workorder.setCreatedAt(SEEDED_AT);
        workorder.setUpdatedAt(SEEDED_AT);
    }

    private UUID seed(WorkorderStatus status, LocalDate scheduledDate, boolean reopened) {
        Workorder workorder = new Workorder();
        stampTimestamps(workorder);
        workorder.setLocationId(LOCATION);
        workorder.setResourceId(BAY);
        workorder.setResourceType(ResourceType.BAY);
        workorder.setStatus(status);
        workorder.setScheduledDate(scheduledDate);
        workorder.setIsReopened(reopened);
        return workorderRepository.saveAndFlush(workorder).getId();
    }
}
