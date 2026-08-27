package com.positivity.catalog.internal.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.catalog.internal.dto.ServiceFactReplayResultDto;
import com.positivity.catalog.internal.dto.SupplierArticleCodeReplayResultDto;
import com.positivity.catalog.service.ProductFactReplayService;
import com.positivity.catalog.service.ServiceFactReplayService;
import com.positivity.catalog.service.SupplierArticleCodeReplayService;
import java.time.Instant;
import org.aopalliance.aop.Advice;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.interceptor.DefaultTransactionAttribute;
import org.springframework.transaction.interceptor.NameMatchTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.SmartTransactionObject;

/**
 * Reproduces defect S1 (#1537): {@code onCommand} used to be {@code @Transactional} and called
 * {@code replayPage}, itself {@code @Transactional} (REQUIRED, so it joins the same transaction). A
 * non-transient {@link org.springframework.dao.DataAccessException} inside {@code replayPage} marks
 * the shared transaction rollback-only via Spring's normal participating-transaction rule; {@code
 * onCommand}'s broad catch swallowed the exception, but the outer commit then threw {@link
 * UnexpectedRollbackException}, which escaped the listener entirely.
 *
 * <p>This uses real Spring transaction machinery — {@link AbstractPlatformTransactionManager}'s
 * actual commit/rollback algorithm via a {@link TransactionInterceptor}-proxied {@link
 * CatalogCommandListener} — rather than reimplementing the propagation rule, so the test exercises
 * the identical mechanism a real {@code JpaTransactionManager} would apply. Only the outer {@code
 * onCommand} proxy is driven by real {@code @Transactional} annotation scanning; the inner replay
 * service (mocked at the interface, so it carries no annotation to scan) is force-wrapped as
 * REQUIRED via name-matching to stand in for {@code ProductFactReplayServiceImpl.replayPage}'s own
 * real {@code @Transactional}.
 */
class CatalogCommandListenerTransactionPropagationTest {

    /**
     * In-memory {@link org.springframework.transaction.PlatformTransactionManager} implementing
     * REQUIRED / rollback-only propagation via Spring's own {@link AbstractPlatformTransactionManager}
     * algorithm, with no real datasource behind it.
     */
    static final class InMemoryTransactionManager extends AbstractPlatformTransactionManager {

        private final ThreadLocal<TxObject> current = new ThreadLocal<>();

        static final class TxObject implements SmartTransactionObject {
            private boolean rollbackOnly;

            @Override
            public boolean isRollbackOnly() {
                return rollbackOnly;
            }
        }

        @Override
        protected Object doGetTransaction() {
            TxObject existing = current.get();
            return existing != null ? existing : new TxObject();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return current.get() != null;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            current.set((TxObject) transaction);
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // No real resource: a successful commit here is a no-op.
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // No real resource: a rollback here is a no-op.
        }

        @Override
        protected void doSetRollbackOnly(DefaultTransactionStatus status) {
            ((TxObject) status.getTransaction()).rollbackOnly = true;
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            current.remove();
        }
    }

    private final ProductFactReplayService productFactReplayService = mock(ProductFactReplayService.class);
    private final ServiceFactReplayService serviceFactReplayService = mock(ServiceFactReplayService.class);
    private final SupplierArticleCodeReplayService supplierArticleCodeReplayService =
            mock(SupplierArticleCodeReplayService.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private static final String REPLAY_COMMAND = """
            {"commandType":"catalog.outbox.replay-requested",
             "payload":{"since":"2026-07-13T10:00:00Z","scope":"PRODUCT"}}
            """;

    /** Proxies {@code target}'s named method(s) as REQUIRED-transactional against {@code txManager}. */
    @SuppressWarnings("unchecked")
    private static <T> T transactionalProxy(T target, Class<T> proxyInterface, InMemoryTransactionManager txManager) {
        NameMatchTransactionAttributeSource source = new NameMatchTransactionAttributeSource();
        source.addTransactionalMethod("*", new DefaultTransactionAttribute());
        Advice advice = new TransactionInterceptor(txManager, source);
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(proxyInterface == null);
        if (proxyInterface != null) {
            factory.setInterfaces(proxyInterface);
        }
        factory.addAdvice(advice);
        return (T) factory.getProxy();
    }

    @Test
    void demonstratesTheDefect_beforeTheFix_transactionalOnCommandLetsUnexpectedRollbackEscape() {
        InMemoryTransactionManager txManager = new InMemoryTransactionManager();

        when(productFactReplayService.replayPage(any(), any(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("simulated permanent DB failure"));
        stubOtherScopesComplete();

        ProductFactReplayService transactionalReplayService =
                transactionalProxy(productFactReplayService, ProductFactReplayService.class, txManager);

        CatalogCommandListener rawListener = new CatalogCommandListener(
                new tools.jackson.databind.ObjectMapper(),
                transactionalReplayService,
                serviceFactReplayService,
                supplierArticleCodeReplayService,
                kafkaTemplate);

        // Simulates the pre-fix state: onCommand itself wrapped as REQUIRED-transactional, joining
        // the same InMemoryTransactionManager the inner replayPage call participates in — exactly
        // what @Transactional on onCommand produced before this fix.
        CatalogCommandListener transactionalListener = transactionalProxy(rawListener, null, txManager);

        assertThatExceptionOfType(UnexpectedRollbackException.class)
                .as("a non-transient failure in replayPage marks the shared transaction rollback-only;"
                        + " onCommand's broad catch swallows the exception, but the outer commit then"
                        + " throws because the transaction it shared with replayPage was marked"
                        + " rollback-only underneath it")
                .isThrownBy(() -> transactionalListener.onCommand(REPLAY_COMMAND));
    }

    @Test
    void confirmsTheFix_onCommandWithoutTransactional_swallowsTheFailureCleanly() {
        InMemoryTransactionManager txManager = new InMemoryTransactionManager();

        when(productFactReplayService.replayPage(any(), any(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("simulated permanent DB failure"));
        stubOtherScopesComplete();

        ProductFactReplayService transactionalReplayService =
                transactionalProxy(productFactReplayService, ProductFactReplayService.class, txManager);

        CatalogCommandListener listener = new CatalogCommandListener(
                new tools.jackson.databind.ObjectMapper(),
                transactionalReplayService,
                serviceFactReplayService,
                supplierArticleCodeReplayService,
                kafkaTemplate);
        // No transactional proxy wraps onCommand itself, matching the fixed production wiring:
        // onCommand carries no @Transactional, so replayPage's own REQUIRED transaction is the only
        // one in play and it fails/commits independently.

        assertThatCode(() -> listener.onCommand(REPLAY_COMMAND))
                .as("with onCommand no longer transactional, replayPage's failure rolls back its own"
                        + " (independent) transaction and the exception is swallowed by onCommand's"
                        + " own catch — nothing marks a shared transaction rollback-only, so no"
                        + " UnexpectedRollbackException is generated at all")
                .doesNotThrowAnyException();
    }

    @Test
    void confirmsTheFix_siblingScopesStillRunAfterOneScopeFailsNonTransiently() {
        when(productFactReplayService.replayPage(any(), any(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("simulated permanent DB failure"));
        stubOtherScopesComplete();

        CatalogCommandListener listener = new CatalogCommandListener(
                new tools.jackson.databind.ObjectMapper(),
                productFactReplayService,
                serviceFactReplayService,
                supplierArticleCodeReplayService,
                kafkaTemplate);

        listener.onCommand("""
                {"commandType":"catalog.outbox.replay-requested",
                 "payload":{"since":"2026-07-13T10:00:00Z"}}
                """);

        verify(serviceFactReplayService).replayPage(any(), any(), anyInt());
        verify(supplierArticleCodeReplayService).replayPage(any(), any(), anyInt());
    }

    private void stubOtherScopesComplete() {
        when(serviceFactReplayService.replayPage(any(), any(), anyInt()))
                .thenReturn(new ServiceFactReplayResultDto(0, null, true, null, Instant.now()));
        when(supplierArticleCodeReplayService.replayPage(any(), any(), anyInt()))
                .thenReturn(new SupplierArticleCodeReplayResultDto(0, null, true, null, Instant.now()));
    }
}
