package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyRequest;
import com.positivity.accounting.internal.dto.BankReconciliationPolicyResponse;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * The policy PUT retry (story S6, #2305): a first-time PUT that lost the {@code (tenant_id, config_key)} race is
 * run once more in a fresh transaction, where the winner's rows exist to lock and diff against.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RetryingAccountingConfigurationService")
class RetryingAccountingConfigurationServiceTest {

    @Mock
    private AccountingConfigurationServiceImpl delegate;

    @InjectMocks
    private RetryingAccountingConfigurationService service;

    private final BankReconciliationPolicyRequest request = new BankReconciliationPolicyRequest();

    private static DataIntegrityViolationException raced() {
        return new DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_accounting_configuration_key\"");
    }

    @Test
    @DisplayName("[M] the loser of a first-time race applies on the retry")
    void theLoserOfARaceAppliesOnTheRetry() {
        BankReconciliationPolicyResponse applied =
                BankReconciliationPolicyResponse.builder().build();
        when(delegate.setBankReconciliationPolicy(request)).thenThrow(raced()).thenReturn(applied);

        assertThat(service.setBankReconciliationPolicy(request)).isSameAs(applied);
        verify(delegate, times(2)).setBankReconciliationPolicy(request);
    }

    @Test
    @DisplayName("a second race is not retried again")
    void aSecondRaceSurfaces() {
        when(delegate.setBankReconciliationPolicy(request)).thenThrow(raced()).thenThrow(raced());

        assertThatThrownBy(() -> service.setBankReconciliationPolicy(request))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(delegate, times(2)).setBankReconciliationPolicy(request);
    }

    @Test
    @DisplayName("a refusal is not retried")
    void aRefusalIsNotRetried() {
        when(delegate.setBankReconciliationPolicy(request))
                .thenThrow(new BankRecException(BankRecErrorCode.VALIDATION_ERROR, "justification is required"));

        assertThatThrownBy(() -> service.setBankReconciliationPolicy(request)).hasMessage("justification is required");
        verify(delegate, times(1)).setBankReconciliationPolicy(request);
    }

    @Test
    @DisplayName("reads and the hard-lock setter pass straight through")
    void passThrough() {
        LocalDate lock = LocalDate.of(2026, 1, 1);
        when(delegate.getHardLockDate()).thenReturn(Optional.of(lock));
        when(delegate.setHardLockDate(lock, "Year-end close")).thenReturn(lock);
        BankReconciliationPolicyResponse policy =
                BankReconciliationPolicyResponse.builder().build();
        when(delegate.getBankReconciliationPolicy()).thenReturn(policy);

        assertThat(service.getHardLockDate()).contains(lock);
        assertThat(service.setHardLockDate(lock, "Year-end close")).isEqualTo(lock);
        assertThat(service.getBankReconciliationPolicy()).isSameAs(policy);
    }
}
