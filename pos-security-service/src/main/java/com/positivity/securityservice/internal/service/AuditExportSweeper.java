package com.positivity.securityservice.internal.service;

import com.positivity.tenancy.TenantIterator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled housekeeping for audit exports (#2408): once per active tenant, with that tenant bound,
 * fail jobs left PENDING or IN_PROGRESS past stale-after (work lost to a restart) and purge jobs
 * past retention. Reads never change a job; this is the only place stale jobs are resolved. The
 * first run waits one interval, so a short-lived context (a test) never reaches it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditExportSweeper {

    private final AuditExportService auditExportService;
    private final TenantIterator tenantIterator;

    @Scheduled(
            fixedDelayString = "${pos.security.audit-export.sweep-interval:PT5M}",
            initialDelayString = "${pos.security.audit-export.sweep-interval:PT5M}")
    public void sweep() {
        tenantIterator.forEachActiveTenant(tenantId -> {
            AuditExportService.SweepResult result = auditExportService.sweepBoundTenant();
            if (result.interrupted() > 0 || result.purged() > 0) {
                log.info(
                        "Audit export sweep: tenant={} interrupted={} purged={}",
                        tenantId,
                        result.interrupted(),
                        result.purged());
            }
        });
    }
}
