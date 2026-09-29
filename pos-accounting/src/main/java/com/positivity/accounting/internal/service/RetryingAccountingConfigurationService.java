package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import java.time.LocalDate;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Concurrency decorator for {@link AccountingConfigurationService} (story S6, #2305), the shape of the bank
 * reconciliation core's {@code RetryingBankStatementService}. It is deliberately <strong>not</strong> transactional.
 *
 * <p>A policy PUT writes each setting through a locked read of its row; a setting that has no row yet is inserted.
 * Two first PUTs of a tenant can therefore both find a setting absent — there is no row to lock — and the one that
 * commits second fails the {@code (tenant_id, config_key)} unique constraint instead of serializing behind the
 * first. That loser runs once more through the delegate's proxy in a fresh transaction, where the winner's row
 * exists, is locked and is diffed against: the second PUT then applies as if it had waited for the first, and its
 * audit row's {@code oldValue} is the winner's value. The reads and the hard-lock setter pass straight through.
 */
@Slf4j
@Service
@Primary
@RequiredArgsConstructor
public class RetryingAccountingConfigurationService implements AccountingConfigurationService {

    private final AccountingConfigurationServiceImpl delegate;

    @Override
    public Optional<LocalDate> getHardLockDate() {
        return delegate.getHardLockDate();
    }

    @Override
    public @NonNull LocalDate setHardLockDate(@NonNull LocalDate hardLockDate, @NonNull String justification) {
        return delegate.setHardLockDate(hardLockDate, justification);
    }

    @Override
    public @NonNull BankReconciliationPolicyResponse getBankReconciliationPolicy() {
        return delegate.getBankReconciliationPolicy();
    }

    @Override
    public @NonNull BankReconciliationPolicyResponse setBankReconciliationPolicy(
            @NonNull BankReconciliationPolicyRequest request) {
        try {
            return delegate.setBankReconciliationPolicy(request);
        } catch (DataIntegrityViolationException raced) {
            log.warn("Bank reconciliation policy update lost a race on a setting's first row; retrying once", raced);
            return delegate.setBankReconciliationPolicy(request);
        }
    }
}
