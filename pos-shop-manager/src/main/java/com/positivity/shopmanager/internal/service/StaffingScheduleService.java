package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.HrScheduleBlock;
import com.positivity.shopmanager.internal.entity.ExtStaffingAssignmentReplica;
import com.positivity.shopmanager.internal.repository.ExtStaffingAssignmentReplicaRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Schedule/availability data from the local staffing-assignment replica (ADR-0044 §6, #877),
 * replacing the retired {@code HrAvailabilityClient}.
 *
 * <p>The retired client called endpoints that either returned data it discarded (the overlay
 * probe) or did not exist ({@code /hr/v1/schedules} — the schedule-block lookup always failed
 * with HR_UNAVAILABLE). Blocks are now derived from ACTIVE staffing assignments at day
 * granularity: an assignment effective across the window yields one SHIFT block covering the
 * overlap. PTO is not modeled anywhere in the platform yet, so no PTO blocks are produced.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StaffingScheduleService {

    private static final String ACTIVE = "ACTIVE";
    private static final String BLOCK_TYPE_SHIFT = "SHIFT";

    private final ExtStaffingAssignmentReplicaRepository assignmentReplicaRepository;

    /** SHIFT blocks for the person derived from assignments overlapping the window. */
    @NonNull
    public List<HrScheduleBlock> getScheduleBlocks(
            @NonNull String personId, @NonNull Instant windowStart, @NonNull Instant windowEnd) {
        UUID personUuid = UUID.fromString(personId);
        return assignmentReplicaRepository.findByPersonIdAndStatus(personUuid, ACTIVE).stream()
                .map(assignment -> toShiftBlock(assignment, windowStart, windowEnd))
                .filter(block -> block != null)
                .toList();
    }

    /** Whether the location has any active staffing data (the old overlay probe, now local). */
    public boolean hasAssignmentData(@NonNull UUID locationId) {
        return assignmentReplicaRepository.existsByLocationIdAndStatus(locationId, ACTIVE);
    }

    private HrScheduleBlock toShiftBlock(
            ExtStaffingAssignmentReplica assignment, Instant windowStart, Instant windowEnd) {
        Instant assignmentStart = assignment.getEffectiveFrom() == null
                ? Instant.MIN
                : assignment.getEffectiveFrom().atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant assignmentEnd = assignment.getEffectiveTo() == null
                ? Instant.MAX
                : assignment
                        .getEffectiveTo()
                        .plusDays(1)
                        .atStartOfDay(ZoneOffset.UTC)
                        .toInstant();
        Instant start = assignmentStart.isAfter(windowStart) ? assignmentStart : windowStart;
        Instant end = assignmentEnd.isBefore(windowEnd) ? assignmentEnd : windowEnd;
        if (!start.isBefore(end)) {
            return null;
        }
        return HrScheduleBlock.builder()
                .blockType(BLOCK_TYPE_SHIFT)
                .startTime(start)
                .endTime(end)
                .build();
    }
}
