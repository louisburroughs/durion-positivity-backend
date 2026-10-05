package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.AccountingTemplateState;
import com.positivity.accounting.internal.repository.AccountingTemplateStateRepository;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes the bound tenant's {@code accounting_template_state} row under {@code SELECT ... FOR
 * UPDATE} (#2526), creating it on first use.
 *
 * <p>The row is what serialises template runs for a tenant: the {@code tenant.created} listener,
 * the startup sweep and a second instance all come through here, and the second to arrive waits
 * until the first has committed, then finds everything already recorded.
 *
 * <p>The first run for a tenant has no row to lock. It is inserted in a transaction of its own, so
 * that losing the race to insert it (the unique key on {@code tenant_id}) costs only that small
 * transaction and not the caller's, which a constraint violation would otherwise poison in
 * Postgres. Either way the row then exists, and the caller's transaction locks it.
 */
@Slf4j
@Component
public class AccountingTemplateStateLock {

    private final AccountingTemplateStateRepository states;
    private final TransactionTemplate ownTransaction;

    public AccountingTemplateStateLock(
            AccountingTemplateStateRepository states, PlatformTransactionManager transactionManager) {
        this.states = states;
        this.ownTransaction = new TransactionTemplate(transactionManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** The bound tenant's state row, locked until the caller's transaction ends. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull AccountingTemplateState acquire() {
        return states.findCurrentForUpdate().orElseGet(() -> {
            try {
                ownTransaction.executeWithoutResult(status -> states.saveAndFlush(new AccountingTemplateState()));
            } catch (DataIntegrityViolationException lostRace) {
                log.debug("Another run created this tenant's accounting template state row first");
            }
            return states.findCurrentForUpdate()
                    .orElseThrow(() -> new IllegalStateException(
                            "accounting_template_state row is missing straight after it was created"));
        });
    }
}
