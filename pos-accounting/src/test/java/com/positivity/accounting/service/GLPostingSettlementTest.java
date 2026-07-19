package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.SettlementPostingCommand;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.service.GLPostingServiceImpl;
import com.positivity.accounting.internal.service.JournalEntryServiceImpl;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for the settlement GL posting methods (story F1c, issue #963, decision D-13). */
@ExtendWith(MockitoExtension.class)
@DisplayName("GLPostingService settlement posting")
class GLPostingSettlementTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC);
    private static final LocalDateTime TXN_DATE = LocalDateTime.of(2026, 6, 15, 0, 0);

    @Spy
    private Clock clock = TEST_CLOCK;

    @Mock
    private JournalEntryServiceImpl journalEntryService;

    @InjectMocks
    private GLPostingServiceImpl service;

    private final UUID jeId = UUID.randomUUID();
    private final UUID cash = UUID.randomUUID();
    private final UUID fees = UUID.randomUUID();
    private final UUID undeposited = UUID.randomUUID();
    private final UUID suspense = UUID.randomUUID();

    private ArgumentCaptor<JournalEntry> capturePosting() {
        ArgumentCaptor<JournalEntry> captor = ArgumentCaptor.forClass(JournalEntry.class);
        when(journalEntryService.createJournalEntry(captor.capture())).thenAnswer(inv -> {
            JournalEntry e = inv.getArgument(0);
            e.setJournalEntryId(jeId);
            return e;
        });
        JournalEntry posted = new JournalEntry();
        posted.setJournalEntryId(jeId);
        posted.setStatus(JournalEntryStatus.POSTED);
        when(journalEntryService.postJournalEntry(eq(jeId), isNull())).thenReturn(posted);
        return captor;
    }

    private static BigDecimal sumDebits(JournalEntry e) {
        return e.getLines().stream().map(JournalEntryLine::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sumCredits(JournalEntry e) {
        return e.getLines().stream().map(JournalEntryLine::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    @DisplayName("postSettlement - fully matched settlement balances Dr Cash+Fees / Cr Undeposited")
    void postSettlement_fullyMatched() {
        ArgumentCaptor<JournalEntry> captor = capturePosting();
        // gross 1000 = fee 30 + net 970; all matched.
        SettlementPostingCommand cmd = new SettlementPostingCommand(
                UUID.randomUUID(),
                cash,
                fees,
                undeposited,
                suspense,
                new BigDecimal("970.00"),
                new BigDecimal("30.00"),
                new BigDecimal("1000.00"),
                BigDecimal.ZERO,
                TXN_DATE,
                "settlement",
                null);

        JournalEntry result = service.postSettlement(cmd);
        assertThat(result.getStatus()).isEqualTo(JournalEntryStatus.POSTED);

        JournalEntry je = captor.getValue();
        // No suspense leg when unmatched gross is zero.
        assertThat(je.getLines()).hasSize(3);
        assertThat(sumDebits(je)).isEqualByComparingTo("1000.00");
        assertThat(sumCredits(je)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("postSettlement - partial match parks unmatched gross in suspense and still balances")
    void postSettlement_partialMatch() {
        ArgumentCaptor<JournalEntry> captor = capturePosting();
        // gross 1000 = fee 30 + net 970; 600 matched, 400 unmatched.
        SettlementPostingCommand cmd = new SettlementPostingCommand(
                UUID.randomUUID(),
                cash,
                fees,
                undeposited,
                suspense,
                new BigDecimal("970.00"),
                new BigDecimal("30.00"),
                new BigDecimal("600.00"),
                new BigDecimal("400.00"),
                TXN_DATE,
                "settlement",
                null);

        service.postSettlement(cmd);
        JournalEntry je = captor.getValue();
        assertThat(je.getLines()).hasSize(4);
        assertThat(sumDebits(je)).isEqualByComparingTo("1000.00");
        assertThat(sumCredits(je)).isEqualByComparingTo("1000.00");
        BigDecimal suspenseCredit = je.getLines().stream()
                .filter(l -> suspense.equals(l.getGlAccountId()))
                .map(JournalEntryLine::getCreditAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(suspenseCredit).isEqualByComparingTo("400.00");
    }

    @Test
    @DisplayName("postSettlementWriteOff - posts balanced two-line Dr Suspense / Cr Adjustment")
    void postSettlementWriteOff_balances() {
        ArgumentCaptor<JournalEntry> captor = capturePosting();
        UUID adjustment = UUID.randomUUID();

        service.postSettlementWriteOff(
                UUID.randomUUID(), suspense, adjustment, new BigDecimal("12.50"), TXN_DATE, "writeoff", null);

        JournalEntry je = captor.getValue();
        assertThat(je.getLines()).hasSize(2);
        assertThat(sumDebits(je)).isEqualByComparingTo("12.50");
        assertThat(sumCredits(je)).isEqualByComparingTo("12.50");
    }
}
