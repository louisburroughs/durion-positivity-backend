package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Raises {@code SERVICE_DUE_REMINDER} follow-up tasks from the service-history read model
 * (Story #1153) — the second automatic feed alongside the declined-service listener.
 *
 * <p>A party/vehicle becomes due once its most recent completed service is at least the
 * effective interval old: the vehicle's care-preference override replicated from
 * pos-vehicle-inventory into {@code ext_vehicle_care_preference} (#1175) where one exists, the
 * module-wide {@code pos.customer.crm.service-due-months} otherwise — the same resolution the
 * {@code service.due} segment attribute uses (#1144). Candidates are queried at the loosest
 * interval in effect (the smallest override or the default, whichever is smaller) and filtered
 * per candidate in Java against one batch replica load, so the query stays fixed-count — never
 * N+1 — and a tight per-vehicle interval is never missed by the SQL cutoff.
 *
 * <p>Idempotency has no event envelope to key on, so the task's {@code source_event_id} is the
 * deterministic {@code service-due:<serviceHistoryId>} of the completion the reminder chases.
 * The unique index makes a rerun, an overlapping instance, or a still-open prior reminder a
 * no-op; a new reminder can only appear after a newer completion starts the next cycle.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "pos.customer.crm",
        name = "service-due-reminders-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ServiceDueReminderJob {

    /** Prefix of the deterministic {@code source_event_id} idempotency key. */
    static final String SOURCE_PREFIX = "service-due:";

    private static final String SYSTEM_ACTOR = "service-due-reminder";

    private final Clock clock;
    private final ServiceHistoryRepository serviceHistoryRepository;
    private final FollowUpTaskRepository followUpTaskRepository;
    private final ExtVehicleCarePreferenceRepository extVehicleCarePreferenceRepository;

    @Value("${pos.customer.crm.service-due-months:6}")
    private int serviceDueMonths = 6;

    /** Per-run ceiling; anything beyond it is picked up by the next run, never lost. */
    @Value("${pos.customer.crm.service-due-reminder-batch-size:500}")
    private int batchSize = 500;

    /**
     * Polls on an interval rather than a daily cron. The due cutoff is computed from the injected
     * {@code Clock}, while a cron trigger fires against the scheduler's real clock; under the
     * accelerated profile a catch-up window covering a year of application time lasts only hours, so
     * a daily cron would fire roughly never and no reminder would be generated. The sweep is
     * cutoff-based and idempotent on {@code source_event_id}, so polling it more often only picks up
     * what has come due. The interval itself is real time, so the accelerated profile overrides the
     * daily default to a one-minute poll (application.yml); a real day at the default 1000x scale
     * would leave years of virtual time between passes.
     */
    @Scheduled(
            fixedDelayString = "${pos.customer.crm.service-due-reminder-interval-ms:86400000}",
            initialDelayString = "${pos.customer.crm.service-due-reminder-initial-delay-ms:60000}")
    public void generateReminders() {
        // The SQL cutoff must be loose enough for the tightest interval in effect anywhere, so
        // a vehicle with a shorter care-preference override still surfaces as a candidate; each
        // candidate is then judged against its own effective interval below (#1175).
        Integer minOverride = extVehicleCarePreferenceRepository.findMinServiceIntervalMonths();
        // A non-positive replica value can only be corrupt data; never let it widen the scan.
        int loosestIntervalMonths =
                minOverride != null && minOverride >= 1 ? Math.min(minOverride, serviceDueMonths) : serviceDueMonths;
        Instant cutoff = cutoffFor(loosestIntervalMonths);
        List<ServiceHistory> candidates =
                serviceHistoryRepository.findServiceDueCandidates(cutoff, SOURCE_PREFIX, PageRequest.of(0, batchSize));
        Map<UUID, Integer> intervalOverrides = intervalOverridesFor(candidates);

        // Two completions at the same max timestamp both satisfy the latest-row query; one
        // reminder per party/vehicle per run is enough.
        Set<String> seenScopes = new HashSet<>();
        int created = 0;
        for (ServiceHistory history : candidates) {
            int intervalMonths = effectiveIntervalMonths(history.getVehicleId(), intervalOverrides);
            if (history.getCompletedAt().compareTo(cutoffFor(intervalMonths)) >= 0) {
                // Candidate under the loosest cutoff but not yet due at its own effective
                // interval; it stays a candidate and is re-judged next run.
                continue;
            }
            if (!seenScopes.add(history.getPartyId() + ":" + history.getVehicleId())) {
                continue;
            }
            FollowUpTask reminder = toReminder(history, intervalMonths);
            try {
                followUpTaskRepository.save(reminder);
                created++;
            } catch (DataIntegrityViolationException e) {
                if (followUpTaskRepository.existsBySourceEventId(reminder.getSourceEventId())) {
                    // Another instance won the race on the unique source_event_id; their task is ours.
                    log.debug(
                            "Service-due reminder already exists for serviceHistoryId={}",
                            history.getServiceHistoryId());
                } else {
                    // Some other integrity problem — surface it, but let the rest of the batch run;
                    // the row stays a candidate and is retried next run.
                    log.warn(
                            "Failed to create service-due reminder for serviceHistoryId={}",
                            history.getServiceHistoryId(),
                            e);
                }
            }
        }
        if (created > 0 || !candidates.isEmpty()) {
            log.info("Service-due reminder run created {} task(s) from {} candidate(s)", created, candidates.size());
        }
    }

    private FollowUpTask toReminder(ServiceHistory history, int intervalMonths) {
        LocalDate lastServiceDate = LocalDate.ofInstant(history.getCompletedAt(), ZoneOffset.UTC);
        String reason = "Vehicle service due — last service completed " + lastServiceDate
                + (history.getWorkorderNumber() != null ? " (workorder " + history.getWorkorderNumber() + ")" : "");
        return FollowUpTask.builder()
                .partyId(history.getPartyId())
                .vehicleId(history.getVehicleId())
                .type(FollowUpType.SERVICE_DUE_REMINDER)
                .status(FollowUpStatus.OPEN)
                .dueDate(lastServiceDate.plusMonths(intervalMonths))
                .sourceWorkorderId(history.getSourceWorkorderId().toString())
                .sourceEventId(SOURCE_PREFIX + history.getServiceHistoryId())
                .reason(reason)
                .createdBy(SYSTEM_ACTOR)
                .build();
    }

    /**
     * Whole UTC calendar months, same as the service.due segment attribute
     * (SegmentResolutionService.monthsSince, #1144): a completion is due once its date is
     * intervalMonths old, regardless of time of day — so the cutoff is the start of the day
     * after the anniversary date, not an instant offset.
     */
    private Instant cutoffFor(int intervalMonths) {
        return LocalDate.now(clock)
                .minusMonths(intervalMonths)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    /**
     * Care-preference interval overrides for the candidates' vehicles, batch-loaded from the
     * {@code ext_vehicle_care_preference} replica in one query (#1175) so the run never goes
     * N+1. Vehicles without an override (or with a tombstoned one) are absent.
     */
    private Map<UUID, Integer> intervalOverridesFor(List<ServiceHistory> candidates) {
        Set<UUID> vehicleIds = candidates.stream()
                .map(ServiceHistory::getVehicleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> byVehicle = new HashMap<>();
        extVehicleCarePreferenceRepository.findAllById(vehicleIds).forEach(preference -> {
            if (preference.getServiceIntervalMonths() != null && preference.getServiceIntervalMonths() >= 1) {
                byVehicle.put(preference.getVehicleId(), preference.getServiceIntervalMonths());
            }
        });
        return byVehicle;
    }

    private int effectiveIntervalMonths(UUID vehicleId, Map<UUID, Integer> intervalOverrides) {
        Integer override = vehicleId != null ? intervalOverrides.get(vehicleId) : null;
        return override != null ? override : serviceDueMonths;
    }
}
