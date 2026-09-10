package com.positivity.tenancy;

import java.util.UUID;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the submitting thread's tenant to tasks run on Spring-managed executors ({@code
 * @Async}, {@code TaskExecutor}). Registered as a {@link TaskDecorator} bean, which Spring Boot
 * applies to its auto-configured executor; a module that builds its own executor sets it
 * explicitly. A task submitted with no tenant bound runs unbound, and clears whatever the pooled
 * thread carried before.
 */
public class TenantContextTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        UUID tenantId = TenantContext.current().orElse(null);
        return () -> {
            if (tenantId == null) {
                TenantContext.clear();
                runnable.run();
            } else {
                TenantContext.runAs(tenantId, runnable);
            }
        };
    }
}
