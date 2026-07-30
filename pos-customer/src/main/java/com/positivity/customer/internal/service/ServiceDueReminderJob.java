package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.entity.ServiceHistory;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * <p>A party/vehicle becomes due once its most recent completed service is at least
 * {@code pos.customer.crm.service-due-months} old — the same module-wide interval the
 * {@code service.due} segment attribute uses (#1144). pos-vehicle-inventory owns per-vehicle
 * care-preference intervals but does not yet emit them; when that feed lands, it replaces this
 * interval, not this job.
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

    @Value("${pos.customer.crm.service-due-months:6}")
    private int serviceDueMonths = 6;

    /** Per-run ceiling; anything beyond it is picked up by the next run, never lost. */
    @Value("${pos.customer.crm.service-due-reminder-batch-size:500}")
    private int batchSize = 500;

    @Scheduled(cron = "${pos.customer.crm.service-due-reminder-cron:0 0 5 * * *}")
    public void generateReminders() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(ZoneOffset.UTC));
        List<ServiceHistory> candidates = serviceHistoryRepository.findServiceDueCandidates(
                now.minusMonths(serviceDueMonths).toInstant(), SOURCE_PREFIX, PageRequest.of(0, batchSize));

        // Two completions at the same max timestamp both satisfy the latest-row query; one
        // reminder per party/vehicle per run is enough.
        Set<String> seenScopes = new HashSet<>();
        int created = 0;
        for (ServiceHistory history : candidates) {
            if (!seenScopes.add(history.getPartyId() + ":" + history.getVehicleId())) {
                continue;
            }
            try {
                followUpTaskRepository.save(toReminder(history));
                created++;
            } catch (DataIntegrityViolationException e) {
                // Another instance won the race on the unique source_event_id; their task is ours.
                log.debug("Service-due reminder already exists for serviceHistoryId={}", history.getServiceHistoryId());
            }
        }
        if (created > 0 || !candidates.isEmpty()) {
            log.info("Service-due reminder run created {} task(s) from {} candidate(s)", created, candidates.size());
        }
    }

    private FollowUpTask toReminder(ServiceHistory history) {
        LocalDate lastServiceDate = LocalDate.ofInstant(history.getCompletedAt(), ZoneOffset.UTC);
        String reason = "Vehicle service due — last service completed " + lastServiceDate
                + (history.getWorkorderNumber() != null ? " (workorder " + history.getWorkorderNumber() + ")" : "");
        return FollowUpTask.builder()
                .partyId(history.getPartyId())
                .vehicleId(history.getVehicleId())
                .type(FollowUpType.SERVICE_DUE_REMINDER)
                .status(FollowUpStatus.OPEN)
                .dueDate(lastServiceDate.plusMonths(serviceDueMonths))
                .sourceWorkorderId(history.getSourceWorkorderId().toString())
                .sourceEventId(SOURCE_PREFIX + history.getServiceHistoryId())
                .reason(reason)
                .createdBy(SYSTEM_ACTOR)
                .build();
    }
}
