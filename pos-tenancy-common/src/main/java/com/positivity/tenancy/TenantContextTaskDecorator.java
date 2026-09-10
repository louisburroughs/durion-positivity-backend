package com.positivity.tenancy;

import java.util.UUID;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the submitting thread's tenant to tasks run on Spring-managed executors ({@code
 * @Async}, {@code TaskExecutor}). Registered as a {@link TaskDecorator} bean, which Spring Boot
 * applies to its auto-configured executor; a module that builds its own executor sets it
 * explicitly.
 *
 * <p>The pooled worker thread is never trusted: whatever it carried before is cleared on entry, the
 * captured tenant (if any) is bound only for the task's duration, and the thread is left unbound
 * afterwards. Restoring a "previous" binding on a pooled thread would hand one task's tenant to the
 * next.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID tenantId = TenantContext.current().orElse(null);
        return () -> {
            TenantContext.clear();
            if (tenantId != null) {
                TenantContext.bind(tenantId);
            }
            try {
                runnable.run();
            } finally {
                TenantContext.clear();
            }
        };
    }
}
