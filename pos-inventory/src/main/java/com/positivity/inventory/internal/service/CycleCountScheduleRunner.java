package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.cyclecount.schedule.CycleCountScheduleRunResultResponse;
import com.positivity.inventory.service.CycleCountScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled driver for recurring cycle-count schedules (odoo-parity I1, issue
 * #1031), mirroring {@link ReplenishmentScanScheduler}.
 *
 * <p>Disabled by default; enable per environment with
 * {@code pos.inventory.cycle-count.schedule.enabled=true}. The pass is
 * idempotent per (schedule, dueDate) — nextDueDate advances transactionally
 * with plan creation and the plan table carries a unique guard — so an
 * aggressive interval cannot create duplicate plans.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "pos.inventory.cycle-count.schedule", name = "enabled", havingValue = "true")
public class CycleCountScheduleRunner {

    private final CycleCountScheduleService cycleCountScheduleService;

    @Scheduled(
            fixedDelayString = "${pos.inventory.cycle-count.schedule.interval-ms:3600000}",
            initialDelayString = "${pos.inventory.cycle-count.schedule.initial-delay-ms:600000}")
    public void runScheduledPass() {
        try {
            CycleCountScheduleRunResultResponse result = cycleCountScheduleService.runDueSchedules();
            log.info(
                    "Scheduled cycle-count schedule pass: schedulesDue={} plansCreated={} plansSkippedExisting={}",
                    result.getSchedulesDue(),
                    result.getPlansCreated(),
                    result.getPlansSkippedExisting());
        } catch (Exception ex) {
            // Scheduled job: never let one failed pass escalate; the next pass retries.
            log.error("Scheduled cycle-count schedule pass failed", ex);
        }
    }
}
