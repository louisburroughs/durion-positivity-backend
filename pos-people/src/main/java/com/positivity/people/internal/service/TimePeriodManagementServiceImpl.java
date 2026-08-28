package com.positivity.people.internal.service;

import com.positivity.people.internal.config.TimePeriodProperties;
import com.positivity.people.internal.dto.CreateTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.enums.TimePeriodStatus;
import com.positivity.people.internal.repository.TimePeriodRepository;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Production write path for pay periods (issue #1527): explicit operator creation and status
 * corrections, plus the scheduled rollover that keeps every tenant with timekeeping activity
 * covered by grid-aligned periods and advances statuses as period dates pass.
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TimePeriodManagementServiceImpl implements TimePeriodManagementService {

    private final TimePeriodRepository timePeriodRepository;
    private final TimekeepingEntryRepository timekeepingEntryRepository;
    private final TimePeriodProperties properties;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull TimePeriodDto createTimePeriod(@NonNull CreateTimePeriodRequest request) {
        if (request.getEndDate().isBefore(request.getStartDate())) {
            throw new IllegalArgumentException("endDate must not be before startDate");
        }
        if (timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                request.getTenantId(), request.getEndDate(), request.getStartDate())) {
            throw new IllegalStateException("An existing time period overlaps " + request.getStartDate() + " to "
                    + request.getEndDate() + " for this tenant");
        }
        TimePeriod period = new TimePeriod();
        period.setTenantId(request.getTenantId());
        period.setStartDate(request.getStartDate());
        period.setEndDate(request.getEndDate());
        period.setStatus(request.getStatus() != null ? request.getStatus() : TimePeriodStatus.OPEN);
        try {
            return toDto(timePeriodRepository.saveAndFlush(period));
        } catch (DataIntegrityViolationException ex) {
            // A concurrent create won the race past the advisory check above: either the
            // (tenant_id, start_date) unique constraint (V8) or the per-tenant no-overlap
            // exclusion constraint (V9) rejected the insert.
            throw new IllegalStateException(
                    "A time period overlapping " + request.getStartDate() + " to " + request.getEndDate()
                            + " already exists for this tenant",
                    ex);
        }
    }

    @Override
    @Transactional
    public @NonNull TimePeriodDto transitionTimePeriod(
            @NonNull UUID timePeriodId, @NonNull TimePeriodStatus targetStatus) {
        TimePeriod period = timePeriodRepository
                .findById(timePeriodId)
                .orElseThrow(() -> new EntityNotFoundException("TimePeriod not found: " + timePeriodId));
        TimePeriodStatus current = period.getStatus();
        if (!isAllowedTransition(current, targetStatus)) {
            throw new IllegalStateException("Transition " + current + " -> " + targetStatus + " is not allowed");
        }
        period.setStatus(targetStatus);
        return toDto(timePeriodRepository.save(period));
    }

    private boolean isAllowedTransition(TimePeriodStatus current, TimePeriodStatus target) {
        return switch (current) {
            case OPEN -> target == TimePeriodStatus.SUBMISSION_CLOSED || target == TimePeriodStatus.PAYROLL_CLOSED;
            // SUBMISSION_CLOSED -> OPEN is the correction path: reopen for late submissions.
            case SUBMISSION_CLOSED -> target == TimePeriodStatus.PAYROLL_CLOSED || target == TimePeriodStatus.OPEN;
            case PAYROLL_CLOSED -> false;
        };
    }

    // Deliberately NOT @Transactional: each repository write commits on its own, so one
    // tenant's unique-constraint race (caught below) cannot poison a surrounding
    // transaction with rollback-only and undo the rest of the pass.
    @Override
    public @NonNull TimePeriodRolloverResult runRollover() {
        // Fail fast on misconfiguration: periodLengthDays <= 0 would make the grid loop
        // non-terminating (plusDays(0)), and maxBackfillPeriods < 1 inverts the backfill floor.
        if (properties.getPeriodLengthDays() <= 0) {
            throw new IllegalStateException("pos.people.time-period.period-length-days must be positive, was "
                    + properties.getPeriodLengthDays());
        }
        if (properties.getMaxBackfillPeriods() < 1) {
            throw new IllegalStateException("pos.people.time-period.max-backfill-periods must be at least 1, was "
                    + properties.getMaxBackfillPeriods());
        }
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));

        int submissionsClosed = advanceStatuses(
                TimePeriodStatus.OPEN,
                TimePeriodStatus.SUBMISSION_CLOSED,
                today.minusDays(properties.getSubmissionCloseGraceDays()));
        int payrollsClosed = advanceStatuses(
                TimePeriodStatus.SUBMISSION_CLOSED,
                TimePeriodStatus.PAYROLL_CLOSED,
                today.minusDays(properties.getPayrollCloseGraceDays()));
        int periodsCreated = createMissingPeriods(today);

        return TimePeriodRolloverResult.builder()
                .periodsCreated(periodsCreated)
                .submissionsClosed(submissionsClosed)
                .payrollsClosed(payrollsClosed)
                .build();
    }

    private int advanceStatuses(TimePeriodStatus from, TimePeriodStatus to, LocalDate endDateBefore) {
        List<TimePeriod> due = timePeriodRepository.findByStatusAndEndDateBefore(from, endDateBefore);
        for (TimePeriod period : due) {
            period.setStatus(to);
        }
        timePeriodRepository.saveAll(due);
        return due.size();
    }

    private int createMissingPeriods(LocalDate today) {
        Set<UUID> tenantIds = new LinkedHashSet<>(timekeepingEntryRepository.findDistinctTenantIds());
        tenantIds.addAll(timePeriodRepository.findDistinctTenantIds());

        int created = 0;
        for (UUID tenantId : tenantIds) {
            created += createMissingPeriodsForTenant(tenantId, today);
        }
        return created;
    }

    private int createMissingPeriodsForTenant(UUID tenantId, LocalDate today) {
        int length = properties.getPeriodLengthDays();
        LocalDate coverageStart = coverageStart(tenantId, today);
        LocalDate backfillFloor = gridStart(today).minusDays((long) (properties.getMaxBackfillPeriods() - 1) * length);
        if (coverageStart.isBefore(backfillFloor)) {
            coverageStart = backfillFloor;
        }

        int created = 0;
        for (LocalDate start = gridStart(coverageStart); !start.isAfter(today); start = start.plusDays(length)) {
            LocalDate end = start.plusDays(length - 1L);
            if (timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                    tenantId, end, start)) {
                continue;
            }
            TimePeriod period = new TimePeriod();
            period.setTenantId(tenantId);
            period.setStartDate(start);
            period.setEndDate(end);
            period.setStatus(TimePeriodStatus.OPEN);
            try {
                timePeriodRepository.saveAndFlush(period);
                created++;
            } catch (DataIntegrityViolationException ex) {
                // Another instance's rollover created the same grid period between our
                // overlap check and the insert; that period exists, which is all we need.
                log.debug("Skipping concurrently created period tenant={} start={}", tenantId, start);
            }
        }
        return created;
    }

    /** Earliest date the tenant's periods must cover: its oldest timekeeping entry, else today. */
    private LocalDate coverageStart(UUID tenantId, LocalDate today) {
        Instant earliestEntry = timekeepingEntryRepository.findEarliestSessionStartByTenantId(tenantId);
        if (earliestEntry == null) {
            return today;
        }
        return LocalDate.ofInstant(earliestEntry, ZoneOffset.UTC);
    }

    /** Start of the grid period containing the given date (grid anchored at anchorDate). */
    private LocalDate gridStart(LocalDate date) {
        int length = properties.getPeriodLengthDays();
        long daysFromAnchor = ChronoUnit.DAYS.between(properties.getAnchorDate(), date);
        return properties.getAnchorDate().plusDays(Math.floorDiv(daysFromAnchor, length) * (long) length);
    }

    private TimePeriodDto toDto(TimePeriod p) {
        return TimePeriodDto.builder()
                .timePeriodId(p.getTimePeriodId())
                .tenantId(p.getTenantId())
                .startDate(p.getStartDate())
                .endDate(p.getEndDate())
                .status(p.getStatus())
                .build();
    }
}
