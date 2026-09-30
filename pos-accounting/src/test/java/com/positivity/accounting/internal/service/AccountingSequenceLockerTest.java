package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.tenancy.TenantResolver;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AccountingSequenceLockerTest {

    private static final String SCOPE = "JE-202607";
    private static final UUID TENANT = UUID.randomUUID();

    @Mock
    private AccountingSequenceRepository sequenceRepository;

    @Mock
    private TenantResolver tenantResolver;

    @InjectMocks
    private AccountingSequenceLocker locker;

    @Test
    void existingRowIsLockedWithoutInsert() {
        AccountingSequence row = row(5L);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.of(row));

        assertThat(locker.lockOrProvision(SCOPE)).isSameAs(row);
        verify(sequenceRepository, never()).insertIfAbsent(any(), any(), any());
    }

    @Test
    void firstUseInsertsThenRereadsUnderTheLock() {
        AccountingSequence row = row(1L);
        when(tenantResolver.require()).thenReturn(TENANT);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty(), Optional.of(row));

        assertThat(locker.lockOrProvision(SCOPE)).isSameAs(row);
        verify(sequenceRepository).insertIfAbsent(eq(TENANT), any(UUID.class), eq(SCOPE));
    }

    @Test
    void losingTheRaceAdoptsTheWinnersRow() {
        AccountingSequence winners = row(4L);
        when(tenantResolver.require()).thenReturn(TENANT);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty(), Optional.of(winners));
        when(sequenceRepository.insertIfAbsent(eq(TENANT), any(UUID.class), eq(SCOPE)))
                .thenReturn(0);

        assertThat(locker.lockOrProvision(SCOPE)).isSameAs(winners);
    }

    @Test
    void rowStillMissingAfterInsertIsAnError() {
        when(tenantResolver.require()).thenReturn(TENANT);
        when(sequenceRepository.findByScopeKey(SCOPE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locker.lockOrProvision(SCOPE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(SCOPE);
    }

    private static AccountingSequence row(long next) {
        AccountingSequence sequence = new AccountingSequence();
        sequence.setScopeKey(SCOPE);
        sequence.setNextValue(next);
        return sequence;
    }
}
