package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.SystemPrompt;
import com.positivity.mcp.internal.repository.SystemPromptRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * {@link SystemPromptWriter} behind Spring's real transaction interceptor, as it runs in production.
 *
 * <p>The other tests call the writer directly, which says nothing about what crosses the
 * {@code @Transactional} boundary. What the Kafka path needs (#2355) is that the repository's own
 * exception leaves the strict variants unchanged, so the listener can classify it, and that their
 * transaction is rolled back rather than committed. The transaction manager is a recording stub:
 * this pins which exception escapes and whether commit or rollback was asked for, not what a
 * database does with either.
 */
@DisplayName("SystemPromptWriter across its transaction boundary (#2355)")
class SystemPromptWriterTransactionTest {

    private SystemPromptRepository repository;
    private PlatformTransactionManager transactionManager;
    private SystemPromptWriter writer;

    @BeforeEach
    void setUp() {
        repository = mock(SystemPromptRepository.class);
        when(repository.findByName(any())).thenReturn(Optional.empty());
        transactionManager = mock(PlatformTransactionManager.class);
        when(transactionManager.getTransaction(any())).thenAnswer(_ -> new SimpleTransactionStatus());

        ProxyFactory factory = new ProxyFactory(new SystemPromptWriter(repository, transactionManager));
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(
                (TransactionManager) transactionManager, new AnnotationTransactionAttributeSource()));
        writer = (SystemPromptWriter) factory.getProxy();
    }

    @Test
    @DisplayName("upsertOrThrow lets the repository's own exception out and rolls its transaction back")
    void upsertOrThrowRethrowsTheOriginalAndRollsBack() {
        DataAccessResourceFailureException lost = new DataAccessResourceFailureException("connection reset");
        when(repository.saveAndFlush(any(SystemPrompt.class))).thenThrow(lost);

        assertThatThrownBy(() -> writer.upsertOrThrow("ROLE_ADMIN", "persona"))
                .isSameAs(lost)
                .matches(RetryableConsumerFailures::isRetryable);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("removeOrThrow lets the repository's own exception out and rolls its transaction back")
    void removeOrThrowRethrowsTheOriginalAndRollsBack() {
        DataAccessResourceFailureException lost = new DataAccessResourceFailureException("connection reset");
        when(repository.findByName("ROLE_CUSTOMER")).thenThrow(lost);

        assertThatThrownBy(() -> writer.removeOrThrow("ROLE_CUSTOMER")).isSameAs(lost);

        verify(transactionManager).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("a permanent rejection leaves upsertOrThrow as itself too, so the listener sees it is not retryable")
    void upsertOrThrowRethrowsAPermanentFailureUnchanged() {
        DataIntegrityViolationException rejected = new DataIntegrityViolationException("duplicate key");
        when(repository.saveAndFlush(any(SystemPrompt.class))).thenThrow(rejected);

        assertThatThrownBy(() -> writer.upsertOrThrow("ROLE_ADMIN", "persona"))
                .isSameAs(rejected)
                .matches(failure -> !RetryableConsumerFailures.isRetryable(failure));
    }

    @Test
    @DisplayName("the strict variants run in a transaction of their own, like the fail-soft ones")
    void strictVariantsRequireANewTransaction() {
        writer.upsertOrThrow("ROLE_ADMIN", "persona");
        writer.removeOrThrow("ROLE_ADMIN");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .extracting(TransactionDefinition::getPropagationBehavior)
                .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("the request-path upsert and remove roll their own transaction back, then swallow a lost connection")
    void failSoftVariantsRollBackThenSwallow() {
        when(repository.findByName(any())).thenThrow(new DataAccessResourceFailureException("connection reset"));

        assertThatCode(() -> writer.upsert("ROLE_ADMIN", "persona")).doesNotThrowAnyException();
        assertThatCode(() -> writer.remove("ROLE_ADMIN")).doesNotThrowAnyException();

        // #2421: the catch is outside the transaction, so the doomed transaction is rolled back,
        // never committed (a commit of a rollback-only transaction is what used to escape).
        verify(transactionManager, org.mockito.Mockito.times(2)).rollback(any());
        verify(transactionManager, never()).commit(any());
    }

    @Test
    @DisplayName("the fail-soft variants run in a transaction of their own too")
    void failSoftVariantsRequireANewTransaction() {
        writer.upsert("ROLE_ADMIN", "persona");
        writer.remove("ROLE_ADMIN");

        ArgumentCaptor<TransactionDefinition> definitions = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(transactionManager, org.mockito.Mockito.times(2)).getTransaction(definitions.capture());
        assertThat(definitions.getAllValues())
                .extracting(TransactionDefinition::getPropagationBehavior)
                .containsOnly(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
}
