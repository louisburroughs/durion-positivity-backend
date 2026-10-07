package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.entity.SessionPolicy;
import com.positivity.order.internal.entity.SessionPolicyChange;
import com.positivity.order.internal.repository.SessionPolicyChangeRepository;
import com.positivity.order.internal.repository.SessionPolicyRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/**
 * CAP:550 S16 (#2512, round 2 LOW-1): {@code ORDER_SESSION_POLICY_UPDATE} is emitted after the policy
 * change commits, never before it and never for a change that rolls back. (A PUT that changes nothing
 * never reaches the writer: {@code SessionPolicyServiceImplTest#noOpPutWritesNothing}.)
 */
@DisplayName("SessionPolicyWriter — the update event follows the commit")
class SessionPolicyWriterTest {

    private final SessionPolicyRepository policies = mock(SessionPolicyRepository.class);
    private final SessionPolicyChangeRepository changes = mock(SessionPolicyChangeRepository.class);
    private final SessionPolicyUpdateEmitter emitter = mock(SessionPolicyUpdateEmitter.class);
    private final SessionPolicyWriter writer = new SessionPolicyWriter(policies, changes, emitter);

    @BeforeEach
    void beginTransaction() {
        TransactionSynchronizationManager.initSynchronization();
        when(policies.saveAndFlush(any())).thenAnswer(inv -> {
            SessionPolicy policy = inv.getArgument(0);
            policy.setVersion(1L);
            return policy;
        });
    }

    @AfterEach
    void endTransaction() {
        TransactionSynchronizationManager.clearSynchronization();
    }

    private void write() {
        writer.write(
                SessionPolicy.builder().currencyCode("USD").build(),
                List.of(SessionPolicyChange.builder()
                        .setting("OVER_SHORT_TOLERANCE")
                        .build()));
    }

    @Test
    @DisplayName("nothing is emitted while the transaction is open; exactly one event after it commits")
    void emitsAfterCommit() {
        write();

        verify(changes).save(any());
        verify(emitter, never()).policyUpdated();

        TransactionSynchronizationUtils.triggerAfterCommit();

        verify(emitter).policyUpdated();
    }

    @Test
    @DisplayName("a change that rolls back emits nothing")
    void rollbackEmitsNothing() {
        write();

        TransactionSynchronizationUtils.triggerAfterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);

        verify(emitter, never()).policyUpdated();
    }

    @Test
    @DisplayName("an emission failure after the commit is logged, not thrown at the caller")
    void emissionFailureDoesNotFailTheCommittedChange() {
        doThrow(new IllegalStateException("receiver down")).when(emitter).policyUpdated();
        write();

        assertThatCode(TransactionSynchronizationUtils::triggerAfterCommit).doesNotThrowAnyException();
        verify(emitter).policyUpdated();
    }
}
