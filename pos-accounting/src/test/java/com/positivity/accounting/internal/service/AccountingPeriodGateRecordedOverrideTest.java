package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.AccountingPeriod;
import com.positivity.accounting.internal.enums.AccountingPeriodStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingPeriodRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * The period gate's entry point for a posting that runs without its caller (CAP:550 S42, #2603; ruling 5): an override
 * accepted and stored earlier applies, and the override audit row names the recorded actor, never the (absent) caller.
 */
@DisplayName("Period gate: an override recorded on the pay command applies at the outbox posting (S42, #2603)")
class AccountingPeriodGateRecordedOverrideTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T01:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate PAID_ON = LocalDate.of(2026, 9, 30);
    private static final UUID ENTRY = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001");

    private final AccountingPeriodRepository periods = mock(AccountingPeriodRepository.class);
    private final AccountingConfigurationService configuration = mock(AccountingConfigurationService.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);

    private AccountingPeriodGate gate;

    @BeforeEach
    void wire() {
        SecurityContextHolder.clearContext(); // the outbox thread has no caller
        when(configuration.getHardLockDate()).thenReturn(Optional.empty());
        when(periods.findWithLockByPeriodCode(anyString())).thenReturn(Optional.empty());
        when(periods.findWithShareLockByPeriodCode(anyString())).thenReturn(Optional.empty());
        gate = new AccountingPeriodGate(
                mock(AccountingPeriodService.class), periods, configuration, auditLogs, TestZoneResolvers.utc(CLOCK));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    private void closed() {
        AccountingPeriod period = new AccountingPeriod();
        period.setStatus(AccountingPeriodStatus.CLOSED);
        when(periods.findWithLockByPeriodCode("2026-09")).thenReturn(Optional.of(period));
        when(periods.findWithShareLockByPeriodCode("2026-09")).thenReturn(Optional.of(period));
    }

    @Test
    @DisplayName("AC5: a closed period with the recorded override posts, and the audit row names the payer")
    void recordedOverrideNamesThePayer() {
        closed();

        gate.assertPostingAllowedWithRecordedOverride(PAID_ON, ENTRY, "Supplier paid on the agreed date", "payer.pat");

        ArgumentCaptor<AccountingAuditLog> audit = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs).save(audit.capture());
        assertThat(audit.getValue().getOperation())
                .isEqualTo(AccountingPeriodGate.AUDIT_OPERATION_PERIOD_OVERRIDE_POST);
        assertThat(audit.getValue().getUserId()).isEqualTo("payer.pat");
        assertThat(audit.getValue().getEntityId()).isEqualTo(ENTRY);
        assertThat(audit.getValue().getJustification()).isEqualTo("Supplier paid on the agreed date");
        assertThat(audit.getValue().getNewValue()).contains("2026-09");
    }

    @Test
    @DisplayName("an open period needs no override and audits nothing")
    void openPeriod() {
        gate.assertPostingAllowedWithRecordedOverride(PAID_ON, ENTRY, "Supplier paid on the agreed date", "payer.pat");

        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("a closed period without a recorded override is PERIOD_CLOSED")
    void closedWithoutOverride() {
        closed();

        assertThatThrownBy(() -> gate.assertPostingAllowedWithRecordedOverride(PAID_ON, ENTRY, null, "payer.pat"))
                .isInstanceOf(AccountingPeriodClosedException.class);
        assertThatThrownBy(() -> gate.assertPostingAllowedWithRecordedOverride(PAID_ON, ENTRY, " ", "payer.pat"))
                .isInstanceOf(AccountingPeriodClosedException.class);
        verify(auditLogs, never()).save(any());
    }

    @Test
    @DisplayName("ruling 6: the hard lock is never overridden, and the time zone still fails closed")
    void hardLockAndZoneStillApply() {
        when(configuration.getHardLockDate()).thenReturn(Optional.of(LocalDate.of(2026, 10, 1)));

        assertThatThrownBy(() -> gate.assertPostingAllowedWithRecordedOverride(
                        PAID_ON, ENTRY, "Supplier paid on the agreed date", "payer.pat"))
                .isInstanceOf(AccountingPeriodHardLockedException.class);

        AccountingPeriodGate unset = new AccountingPeriodGate(
                mock(AccountingPeriodService.class), periods, configuration, auditLogs, TestZoneResolvers.unset(CLOCK));
        assertThatThrownBy(() -> unset.assertPostingAllowedWithRecordedOverride(
                        PAID_ON, ENTRY, "Supplier paid on the agreed date", "payer.pat"))
                .isInstanceOf(AccountingTimeZoneUnsetException.class);
        assertThatThrownBy(() -> unset.assertPaymentDateAllowed(PAID_ON, null))
                .isInstanceOf(AccountingTimeZoneUnsetException.class);
    }

    @Test
    @DisplayName("the caller's own override still needs the caller's authority: no caller, no override")
    void callerOverrideWithoutACaller() {
        closed();

        assertThatThrownBy(() -> gate.assertPostingAllowed(PAID_ON, ENTRY, "Supplier paid on the agreed date"))
                .isInstanceOf(AccountingPeriodClosedException.class);
        assertThatThrownBy(() -> gate.assertPaymentDateAllowed(PAID_ON, "Supplier paid on the agreed date"))
                .isInstanceOf(AccountingPeriodClosedException.class);
    }
}
