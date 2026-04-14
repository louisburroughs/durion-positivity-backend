package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.client.HrAvailabilityClient;
import com.positivity.shopmanager.internal.dto.HrScheduleBlock;
import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.entity.TravelBlock;
import com.positivity.shopmanager.internal.repository.AppointmentRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.TravelBlockRepository;
import com.positivity.shopmanager.service.MechanicAvailabilityService;
import com.positivity.shopmanager.service.dto.ConflictBlock;
import com.positivity.shopmanager.service.dto.MechanicAvailabilityResult;
import com.positivity.shopmanager.service.enums.AvailabilityStatus;
import com.positivity.shopmanager.service.enums.ConflictReasonCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates HR schedule data (shifts, PTO), internal appointments, and travel
 * blocks
 * to compute mechanic availability for a requested time window.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MechanicAvailabilityServiceImpl implements MechanicAvailabilityService {

    private static final String RESOURCE_TYPE_MECHANIC = "MECHANIC";
    private static final String BLOCK_TYPE_SHIFT = "SHIFT";
    private static final String BLOCK_TYPE_PTO = "PTO";

    private final MechanicRepository mechanicRepository;
    private final AppointmentRepository appointmentRepository;
    private final TravelBlockRepository travelBlockRepository;
    private final HrAvailabilityClient hrAvailabilityClient;

    @Override
    public @NonNull MechanicAvailabilityResult queryAvailability(
            @NonNull String personId, @NonNull Instant windowStart, @NonNull Instant windowEnd) {

        if (!windowStart.isBefore(windowEnd)) {
            throw new IllegalArgumentException(
                    "windowStart must be before windowEnd, got: " + windowStart + " >= " + windowEnd);
        }

        var mechanic = mechanicRepository
                .findByPersonId(personId)
                .orElseThrow(() -> new IllegalArgumentException("Mechanic not found for personId: " + personId));

        // Throws HrUnavailableException (wrapped RestClientException) on HR system
        // failure —
        // GlobalExceptionHandler translates that to HTTP 503 HR_UNAVAILABLE.
        List<HrScheduleBlock> hrBlocks = hrAvailabilityClient.getScheduleBlocks(personId, windowStart, windowEnd);

        List<ConflictBlock> conflicts = new ArrayList<>();

        // AC3: Off-shift gaps inside the requested window
        conflicts.addAll(buildOffShiftConflicts(hrBlocks, windowStart, windowEnd));

        // AC2: PTO blocks overlapping the window
        hrBlocks.stream()
                .filter(b -> BLOCK_TYPE_PTO.equals(b.getBlockType()))
                .filter(b -> overlaps(b.getStartTime(), b.getEndTime(), windowStart, windowEnd))
                .map(b -> ConflictBlock.builder()
                        .startTime(b.getStartTime())
                        .endTime(b.getEndTime())
                        .reasonCode(ConflictReasonCode.ON_PTO)
                        .detail("HR PTO block")
                        .build())
                .forEach(conflicts::add);

        // AC4: Overlapping appointments
        List<Appointment> appointments =
                appointmentRepository.findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
                        personId, RESOURCE_TYPE_MECHANIC, windowEnd, windowStart);
        appointments.stream()
                .map(a -> ConflictBlock.builder()
                        .startTime(a.getStartAt())
                        .endTime(a.getEndAt())
                        .reasonCode(ConflictReasonCode.ASSIGNED_APPOINTMENT)
                        .detail("Assigned appointment: " + a.getAppointmentId())
                        .build())
                .forEach(conflicts::add);

        // AC5: Travel blocks
        List<TravelBlock> travelBlocks = travelBlockRepository.findOverlapping(personId, windowStart, windowEnd);
        travelBlocks.stream()
                .map(t -> ConflictBlock.builder()
                        .startTime(t.getStartTime())
                        .endTime(t.getEndTime())
                        .reasonCode(ConflictReasonCode.TRAVEL_BLOCK)
                        .detail(t.getDescription())
                        .build())
                .forEach(conflicts::add);

        AvailabilityStatus status = computeStatus(conflicts, windowStart, windowEnd);

        return MechanicAvailabilityResult.builder()
                .mechanicId(mechanic.getMechanicId())
                .personId(personId)
                .overallStatus(status)
                .windowStart(windowStart)
                .windowEnd(windowEnd)
                .conflicts(List.copyOf(conflicts))
                .build();
    }

    /**
     * Returns AVAILABLE if no conflicts, UNAVAILABLE if any single conflict
     * spans the entire window, and PARTIALLY_AVAILABLE otherwise.
     */
    private AvailabilityStatus computeStatus(List<ConflictBlock> conflicts, Instant windowStart, Instant windowEnd) {
        if (conflicts.isEmpty()) {
            return AvailabilityStatus.AVAILABLE;
        }
        boolean fullyCovered = conflicts.stream()
                .anyMatch(c -> !c.getStartTime().isAfter(windowStart)
                        && !c.getEndTime().isBefore(windowEnd));
        return fullyCovered ? AvailabilityStatus.UNAVAILABLE : AvailabilityStatus.PARTIALLY_AVAILABLE;
    }

    private boolean overlaps(Instant start, Instant end, Instant windowStart, Instant windowEnd) {
        return start.isBefore(windowEnd) && end.isAfter(windowStart);
    }

    private List<ConflictBlock> buildOffShiftConflicts(
            List<HrScheduleBlock> hrBlocks, Instant windowStart, Instant windowEnd) {
        List<TimeRange> uncovered = new ArrayList<>(List.of(new TimeRange(windowStart, windowEnd)));

        List<TimeRange> shiftRanges = hrBlocks.stream()
                .filter(b -> BLOCK_TYPE_SHIFT.equals(b.getBlockType()))
                .map(b ->
                        new TimeRange(maxInstant(b.getStartTime(), windowStart), minInstant(b.getEndTime(), windowEnd)))
                .filter(range -> range.start().isBefore(range.end()))
                .sorted(Comparator.comparing(TimeRange::start))
                .toList();

        for (TimeRange shift : shiftRanges) {
            uncovered = subtractRange(uncovered, shift);
            if (uncovered.isEmpty()) {
                break;
            }
        }

        return uncovered.stream()
                .map(gap -> ConflictBlock.builder()
                        .startTime(gap.start())
                        .endTime(gap.end())
                        .reasonCode(ConflictReasonCode.OFF_SHIFT)
                        .detail("Mechanic is off shift during this window segment")
                        .build())
                .toList();
    }

    private List<TimeRange> subtractRange(List<TimeRange> ranges, TimeRange covered) {
        List<TimeRange> remaining = new ArrayList<>();
        for (TimeRange range : ranges) {
            if (!overlaps(range.start(), range.end(), covered.start(), covered.end())) {
                remaining.add(range);
                continue;
            }

            if (covered.start().isAfter(range.start())) {
                remaining.add(new TimeRange(range.start(), covered.start()));
            }
            if (covered.end().isBefore(range.end())) {
                remaining.add(new TimeRange(covered.end(), range.end()));
            }
        }
        return remaining;
    }

    private Instant maxInstant(Instant left, Instant right) {
        return left.isAfter(right) ? left : right;
    }

    private Instant minInstant(Instant left, Instant right) {
        return left.isBefore(right) ? left : right;
    }

    private record TimeRange(Instant start, Instant end) {}
}
