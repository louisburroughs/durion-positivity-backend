package com.positivity.workorder.internal.service;

import com.positivity.tenancy.TenantIterator;
import com.positivity.workorder.internal.repository.WorkorderRepository;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves workorders left {@code ASSIGNED} by the old rule back to {@code APPROVED} (#2011).
 *
 * <p>Before #2011, {@code ASSIGNED} followed the technician alone, so the table holds rows that say
 * a job is ready to be worked while nobody is on it, or while it stands nowhere — the states the new
 * rule makes unreachable. Those rows do not repair themselves: nothing revisits a workorder's status
 * until somebody changes one of its assignments, and until then the shop dashboard keeps showing the
 * job as assigned.
 *
 * <p>Java rather than a Flyway migration, and through
 * {@link WorkorderStateMachine#reconcileAssigned}, because the correction is a status transition like
 * any other: it must leave a status history row and publish a {@code workorder.events.v1} status
 * event so pos-shop-manager's {@code ext_workorder_replica} catches up. SQL could write the status
 * and the history row, but not the fact — and a replica that silently disagrees with the owner is
 * worse than the state being corrected.
 *
 * <p>Idempotent by construction: a corrected workorder no longer matches the query, so re-running
 * finds nothing. It runs once per boot rather than once ever, which is what makes it safe on a
 * rolling deploy and on any environment restored from an older snapshot; the steady-state cost is one
 * indexed query per tenant.
 *
 * <p>Disable with {@code pos.workorder.assigned-invariant-migration.enabled=false}.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.workorder.assigned-invariant-migration",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class AssignedInvariantMigrationService implements ApplicationRunner {

    private static final String ACTOR = "system";
    private static final String REASON = "ASSIGNED requires technician and bay/mobile unit";

    private final WorkorderRepository workorderRepository;
    private final WorkorderStateMachine stateMachine;
    private final TenantIterator tenantIterator;
    private final TransactionTemplate transaction;

    public AssignedInvariantMigrationService(
            @NonNull WorkorderRepository workorderRepository,
            @NonNull WorkorderStateMachine stateMachine,
            @NonNull TenantIterator tenantIterator,
            @NonNull PlatformTransactionManager transactionManager) {
        this.workorderRepository = workorderRepository;
        this.stateMachine = stateMachine;
        this.tenantIterator = tenantIterator;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /**
     * One pass per tenant (ADR-0062 §3): workorders and their assignments are tenant-scoped, so the
     * query and the corrections both run inside a tenant binding. A failure is logged and the next
     * tenant is still visited — a migration that cannot finish must not stop the service from
     * starting, and what it did not correct it will find again on the next boot.
     */
    @Override
    public void run(ApplicationArguments args) {
        tenantIterator.forEachActiveTenant(tenantId -> {
            try {
                transaction.executeWithoutResult(status -> migrateForTenant());
            } catch (RuntimeException e) {
                log.error(
                        "ASSIGNED-invariant migration failed for tenant {}; it will be retried next boot", tenantId, e);
            }
        });
    }

    void migrateForTenant() {
        List<UUID> stranded = workorderRepository.findAssignedWithoutTechnicianAndPosition();
        if (stranded.isEmpty()) {
            log.debug("ASSIGNED-invariant migration found no workorder to correct");
            return;
        }
        // Reconciled rather than transitioned outright. The query's answer is already stale by the
        // time it is iterated — on a rolling deploy another instance can fill the missing technician
        // or position in between — and an unconditional transition would demote a workorder that has
        // just become genuinely ASSIGNED, or throw on one that has moved to a status with no path
        // back to APPROVED and take the rest of the tenant's pass down with it. reconcileAssigned
        // re-reads each row under its lock and acts only if the invariant is still broken, so the
        // list is a work queue rather than a verdict.
        for (UUID workorderId : stranded) {
            stateMachine.reconcileAssigned(workorderId, ACTOR, REASON);
        }
        log.info(
                "ASSIGNED-invariant migration reconciled {} stranded workorder(s): {} (#2011)",
                stranded.size(),
                stranded);
    }
}
