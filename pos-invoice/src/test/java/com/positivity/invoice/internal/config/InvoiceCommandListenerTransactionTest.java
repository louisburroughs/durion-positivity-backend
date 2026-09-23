package com.positivity.invoice.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

import com.positivity.invoice.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Pins the command listener's transaction shape against a real transaction manager, which the
 * mock-based {@link InvoiceCommandListenerTest} cannot do (#2146).
 *
 * <p>{@code createInvoice} is transactional. An exception leaving it marks the transaction it
 * joined rollback-only, so if the listener ran it inside a transaction of its own the listener's
 * catch-and-drop would still end in {@code UnexpectedRollbackException} and the container would
 * retry and dead-letter the command. A failed generation command is dropped without a mark by
 * contract, so the assertion here is that nothing escapes and nothing is recorded.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("InvoiceCommandListener transaction shape")
class InvoiceCommandListenerTransactionTest {

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    private final AtomicBoolean sawActiveTransaction = new AtomicBoolean(false);
    private InvoiceCommandListener listener;
    private String commandId;

    @BeforeEach
    void setUp() {
        TenantContext.bind(TenantTestSupport.TENANT_A);
        commandId = UUID.randomUUID().toString();
        listener = new InvoiceCommandListener(
                Clock.systemUTC(),
                new ObjectMapper(),
                mock(OutboxReplayService.class),
                transactionalFailingInvoiceService(),
                processedEventRepository,
                transactionManager);
    }

    @AfterEach
    void tearDown() {
        if (processedEventRepository.existsById(commandId)) {
            processedEventRepository.deleteById(commandId);
        }
        TenantContext.clear();
    }

    @Test
    @DisplayName("A permanent createInvoice failure is dropped without a mark and does not throw")
    void permanentFailureIsDroppedUnrecorded() {
        assertThatCode(() -> listener.onCommand(generationCommand(commandId))).doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndNothingWasRecorded();
    }

    /**
     * The shape the defect had: the whole command inside one enclosing transaction, as a
     * {@code @Transactional} listener method would run it. The handler's failure must not mark that
     * transaction rollback-only, or its commit throws {@code UnexpectedRollbackException}.
     */
    @Test
    @DisplayName("Inside an enclosing transaction, the handler's failure does not poison its commit")
    void permanentFailureDoesNotPoisonAnEnclosingTransaction() {
        TransactionTemplate enclosing = new TransactionTemplate(transactionManager);

        assertThatCode(() -> enclosing.executeWithoutResult(_ -> listener.onCommand(generationCommand(commandId))))
                .doesNotThrowAnyException();

        assertHandlerFailedInsideTransactionAndNothingWasRecorded();
    }

    private void assertHandlerFailedInsideTransactionAndNothingWasRecorded() {
        assertThat(sawActiveTransaction.get())
                .as("createInvoice must have run inside a transaction for this test to prove anything")
                .isTrue();
        assertThat(processedEventRepository.existsById(commandId))
                .as("a failed generation command is dropped without a processed mark")
                .isFalse();
    }

    private static String generationCommand(String commandId) {
        return """
                {"commandType":"invoice.generation-requested","commandId":"%s",
                 "payload":{"workorderId":"%s"}}
                """.formatted(commandId, UUID.randomUUID());
    }

    /**
     * An {@link InvoiceService} whose every method is transactional, exactly as the container would
     * apply it, and whose {@code createInvoice} throws after noting whether a transaction was active.
     */
    private InvoiceService transactionalFailingInvoiceService() {
        InvoiceService failing = (InvoiceService) Proxy.newProxyInstance(
                InvoiceService.class.getClassLoader(), new Class<?>[] {InvoiceService.class}, (_, method, _) -> {
                    if ("createInvoice".equals(method.getName())) {
                        sawActiveTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                        throw new IllegalStateException("simulated permanent business failure");
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        ProxyFactory factory = new ProxyFactory(failing);
        factory.addInterface(InvoiceService.class);
        factory.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
        return (InvoiceService) factory.getProxy();
    }
}
