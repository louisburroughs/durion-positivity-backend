package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Writes a single {@code system_prompts} row in its own transaction (#1613).
 *
 * <p>A separate bean, not a private method on {@link RolePersonaRefresher}, for two reasons that
 * both come down to transaction boundaries.
 *
 * <p>{@code REQUIRES_NEW} is load-bearing: the on-miss fetch runs inside
 * {@code RolePromptResolverImpl.assemble}, which is {@code @Transactional(readOnly = true)}. A save
 * on that transaction would not flush at all under Hibernate's read-only flush mode, so the persona
 * would be resolved for the current request and silently never persisted — every later request would
 * miss and re-fetch it. Suspending into a new transaction is what makes the write actually land.
 *
 * <p>And it has to be a different bean: Spring's transaction proxy is bypassed by self-invocation,
 * so a {@code @Transactional} method called from a sibling method of the same class does nothing at
 * all — the annotation would look right and have no effect.
 *
 * <p>Per row rather than per batch, so one bad persona cannot roll back the rest of a sync.
 *
 * <p>The fail-soft pair ({@link #upsert}, {@link #remove}) opens its transaction through a
 * {@link TransactionTemplate} and catches outside it, never inside a {@code @Transactional} method
 * (#2421). A repository call that throws has already marked the transaction rollback-only (Spring
 * Data's own transactional proxy and Hibernate both do), so a catch inside the boundary only defers
 * the failure: the interceptor then commits a doomed transaction and throws
 * {@code UnexpectedRollbackException} out of a method whose contract is that it does not throw.
 */
@Component
public class SystemPromptWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SystemPromptWriter.class);

    private final SystemPromptRepository systemPromptRepository;
    private final TransactionTemplate requiresNew;

    public SystemPromptWriter(
            @NonNull SystemPromptRepository systemPromptRepository,
            @NonNull PlatformTransactionManager transactionManager) {
        this.systemPromptRepository = systemPromptRepository;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Fail-soft: a row that cannot be written is logged, never propagated to the caller.
     *
     * <p>{@code saveAndFlush}, not {@code save}, is what makes that true. A plain {@code save} only
     * queues the insert; a unique-constraint violation on {@code system_prompts.name} would then
     * surface at commit — after this catch block, in the transaction interceptor — and propagate out
     * of a method whose whole contract is that it does not. Flushing inside the try brings the
     * failure back where it can be caught. That race is reachable: two concurrent requests from
     * users of a newly created role both miss, both fetch, and both try to insert the same row.
     *
     * <p>The catch sits outside the {@code REQUIRES_NEW} transaction, so the failed write is rolled
     * back before it is swallowed and a commit-time failure is caught too (#2421).
     */
    public void upsert(@NonNull String name, @NonNull String content) {
        try {
            requiresNew.executeWithoutResult(_ -> writeRow(name, content));
        } catch (RuntimeException exception) {
            // The losing side of that race lands here. The winner wrote the same rendered persona,
            // so the outcome is already correct and the request continues.
            LOGGER.warn("Failed to persist role persona prompt name={}", name, exception);
        }
    }

    /**
     * {@link #upsert} for the Kafka path: the same write, but a failure propagates to the caller.
     *
     * <p>A request can carry on without the row; a consumed event cannot, because returning
     * normally commits its offset and nothing brings the persona back. So nothing is caught here.
     * The exception leaves through the transaction proxy, which rolls this method's own
     * {@code REQUIRES_NEW} transaction back and rethrows the original, and the listener decides
     * whether it is one the container should retry (ADR-0044 §4, #2355).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void upsertOrThrow(@NonNull String name, @NonNull String content) {
        writeRow(name, content);
    }

    private void writeRow(@NonNull String name, @NonNull String content) {
        Optional<SystemPrompt> existing = systemPromptRepository.findByName(name);
        if (existing.isPresent()) {
            SystemPrompt prompt = existing.get();
            if (!content.equals(prompt.getContent())) {
                prompt.setContent(content);
                systemPromptRepository.saveAndFlush(prompt);
                LOGGER.info("Updated role persona prompt name={}", name);
            }
            return;
        }

        SystemPrompt prompt = new SystemPrompt();
        prompt.setName(name);
        prompt.setContent(content);
        systemPromptRepository.saveAndFlush(prompt);
        LOGGER.info("Seeded role persona prompt name={}", name);
    }

    /**
     * Drops a role's persona row, for a role that is no longer eligible for one.
     *
     * <p>Absent is the normal case and not an error: most roles never had a row to remove. Fail-soft
     * like {@link #upsert}, with its catch outside the transaction for the same reason.
     */
    public void remove(@NonNull String name) {
        try {
            requiresNew.executeWithoutResult(_ -> deleteRow(name));
        } catch (RuntimeException exception) {
            LOGGER.warn("Failed to remove role persona prompt name={}", name, exception);
        }
    }

    /** {@link #remove} for the Kafka path: a failure propagates, for the reason {@link #upsertOrThrow} gives. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void removeOrThrow(@NonNull String name) {
        deleteRow(name);
    }

    private void deleteRow(@NonNull String name) {
        systemPromptRepository.findByName(name).ifPresent(prompt -> {
            systemPromptRepository.delete(prompt);
            systemPromptRepository.flush();
            LOGGER.info("Removed role persona prompt name={} (role is no longer persona-eligible)", name);
        });
    }
}
