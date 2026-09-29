package com.positivity.accounting.internal.bankrec.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.lenient;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.enums.SettlementState;
import com.positivity.accounting.internal.bankrec.enums.SourceKind;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Builders for the bank reconciliation unit tests (story S4, #2303), and an in-memory ledger that answers
 * {@link JournalEntryLineRepository}'s reconciliation queries from a list of lines — so a test that
 * drops a bound or a status filter from the query's caller sees the difference.
 */
final class BankRecFixtures {

    static final UUID ACCOUNT_ID = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    static final UUID RECON_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900001");
    static final UUID STATEMENT_ID = UUID.fromString("01936e5e-7890-7a3d-8b6e-4d5678900002");
    static final LocalDate START = LocalDate.of(2026, 9, 1);
    static final LocalDate END = LocalDate.of(2026, 9, 30);

    private BankRecFixtures() {}

    static FunctionalCurrency usd() {
        return new FunctionalCurrency(new LedgerCurrency("USD"));
    }

    static BigDecimal amount(String value) {
        return new BigDecimal(value);
    }

    /** An IN_PROGRESS reconciliation of September 2026 on {@link #ACCOUNT_ID}. */
    static BankReconciliation reconciliation() {
        BankReconciliation recon = new BankReconciliation(RECON_ID);
        recon.setGlAccountId(ACCOUNT_ID);
        recon.setAccountCode("1000");
        recon.setAccountName("Cash");
        recon.setStatementId(STATEMENT_ID);
        recon.setStatementStartDate(START);
        recon.setStatementEndDate(END);
        recon.setCurrency("USD");
        recon.setStatementOpeningBalance(amount("1000.0000"));
        recon.setStatementClosingBalance(amount("1000.0000"));
        recon.setGlEndingBalance(BigDecimal.ZERO);
        recon.setStatus(ReconciliationStatus.IN_PROGRESS);
        recon.setAccountingPeriodCode("2026-09");
        return recon;
    }

    static BankStatement statement(UUID id, LocalDate start, LocalDate end, String ack) {
        BankStatement statement = new BankStatement();
        statement.setStatementId(id);
        statement.setGlAccountId(ACCOUNT_ID);
        statement.setSourceKind(SourceKind.MANUAL_ENTRY);
        statement.setStartDate(start);
        statement.setEndDate(end);
        statement.setOpeningBalance(amount("1000.0000"));
        statement.setClosingBalance(amount("1250.0000"));
        statement.setActivityTotal(amount("250.0000"));
        statement.setCurrency("USD");
        statement.setGapAcknowledgement(ack);
        statement.setStatus(BankStatementStatus.COMMITTED);
        return statement;
    }

    static BankTransaction transaction(String signedAmount, LocalDate date) {
        BankTransaction t = new BankTransaction();
        t.setBankTransactionId(UUIDv7Generator.generate());
        t.setGlAccountId(ACCOUNT_ID);
        t.setStatementId(STATEMENT_ID);
        t.setSourceKind(SourceKind.MANUAL_ENTRY);
        t.setSettlementState(SettlementState.POSTED);
        t.setTransactionDate(date);
        t.setSignedAmount(amount(signedAmount));
        t.setCurrency("USD");
        t.setDescription("BANK ROW");
        t.setNormalizedDescription("BANK ROW");
        t.setStatus(BankTransactionStatus.UNMATCHED);
        return t;
    }

    static JournalEntry entry(LocalDate date, JournalEntryStatus status) {
        JournalEntry entry = new JournalEntry();
        entry.setJournalEntryId(UUIDv7Generator.generate());
        entry.setTransactionDate(date.atTime(12, 0));
        entry.setStatus(status);
        entry.setEntryNumber("JE-" + date.getYear() + String.format("%02d", date.getMonthValue()) + "-0001");
        entry.setDescription("Ledger entry");
        return entry;
    }

    /** A line on {@link #ACCOUNT_ID}: positive = debit, negative = credit. */
    static JournalEntryLine line(JournalEntry entry, String signedAmount) {
        return line(entry, signedAmount, ACCOUNT_ID);
    }

    static JournalEntryLine line(JournalEntry entry, String signedAmount, UUID account) {
        BigDecimal value = amount(signedAmount);
        JournalEntryLine line = new JournalEntryLine();
        line.setLineId(UUIDv7Generator.generate());
        line.setJournalEntry(entry);
        line.setGlAccountId(account);
        line.setDebitAmount(value.signum() > 0 ? value : BigDecimal.ZERO);
        line.setCreditAmount(value.signum() < 0 ? value.negate() : BigDecimal.ZERO);
        line.setDescription("Ledger line");
        entry.getLines().add(line);
        return line;
    }

    /** A POSTED line dated {@code date}. */
    static JournalEntryLine postedLine(String signedAmount, LocalDate date) {
        return line(entry(date, JournalEntryStatus.POSTED), signedAmount);
    }

    static BankReconciliationOutstandingItem item(
            OutstandingItemSide side, OutstandingItemKind kind, String signedAmount, LocalDate itemDate) {
        BankReconciliationOutstandingItem item = new BankReconciliationOutstandingItem();
        item.setOutstandingItemId(UUIDv7Generator.generate());
        item.setGlAccountId(ACCOUNT_ID);
        item.setSide(side);
        item.setItemKind(kind);
        item.setSignedAmount(amount(signedAmount));
        item.setItemDate(itemDate);
        item.setStatus(OutstandingItemStatus.OPEN);
        item.setRegisteredInReconciliationId(RECON_ID);
        item.setRegisteredBy("preparer");
        if (side == OutstandingItemSide.LEDGER) {
            item.setGlLineId(UUIDv7Generator.generate());
        } else {
            item.setBankTransactionId(UUIDv7Generator.generate());
        }
        return item;
    }

    static BankReconciliationAdjustment adjustment(
            BankReconciliation owner, BankAdjustmentType type, String amount, UUID journalEntryId) {
        BankReconciliationAdjustment a = new BankReconciliationAdjustment();
        a.setAdjustmentId(UUIDv7Generator.generate());
        a.setReconciliation(owner);
        a.setAdjustmentType(type);
        a.setAmount(amount(amount));
        a.setJournalEntryId(journalEntryId);
        a.setStatus(AdjustmentStatus.POSTED);
        return a;
    }

    /** Terms with {@code difference} and {@code openingDifference} set and every other amount zero. */
    static Terms terms(String difference, String openingDifference) {
        BigDecimal z = BigDecimal.ZERO;
        return new Terms(
                z,
                z,
                z,
                z,
                z,
                z,
                z,
                amount(difference),
                z,
                z,
                z,
                z,
                z,
                openingDifference == null ? null : amount(openingDifference));
    }

    /** A snapshot with the given terms and nothing unexplained. */
    static ReconciliationSnapshot snapshot(Terms terms) {
        return new ReconciliationSnapshot(
                terms, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of());
    }

    /**
     * An in-memory ledger: {@code findPostedLinesOnAccountBetween}, {@code findLinesOnAccountForEntries},
     * {@code getAccountBalanceAsOf} and {@code lockByIds} answer from {@code lines} exactly as the JPQL does.
     */
    static final class FakeLedger {

        final List<JournalEntryLine> lines = new ArrayList<>();

        FakeLedger(JournalEntryLineRepository repository) {
            lenient()
                    .when(repository.findPostedLinesOnAccountBetween(any(), any(), any()))
                    .thenAnswer(inv -> posted(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
            lenient()
                    .when(repository.findLinesOnAccountForEntries(any(), anyCollection()))
                    .thenAnswer(inv -> ofEntries(inv.getArgument(0), inv.getArgument(1)));
            lenient()
                    .when(repository.getAccountBalanceAsOf(any(), any()))
                    .thenAnswer(inv -> balance(inv.getArgument(0), inv.getArgument(1)));
            lenient().when(repository.lockByIds(anyCollection())).thenAnswer(inv -> byIds(inv.getArgument(0)));
            lenient().when(repository.findAllById(anyCollection())).thenAnswer(inv -> byIds(inv.getArgument(0)));
            lenient()
                    .when(repository.findById(any()))
                    .thenAnswer(inv -> lines.stream()
                            .filter(l -> l.getLineId().equals(inv.getArgument(0)))
                            .findFirst());
            lenient()
                    .when(repository.findByJournalEntry_JournalEntryId(any()))
                    .thenAnswer(inv -> lines.stream()
                            .filter(l -> l.getJournalEntry().getJournalEntryId().equals(inv.getArgument(0)))
                            .toList());
        }

        JournalEntryLine add(JournalEntryLine line) {
            lines.add(line);
            return line;
        }

        private List<JournalEntryLine> posted(UUID account, LocalDateTime from, LocalDateTime to) {
            return lines.stream()
                    .filter(l -> l.getGlAccountId().equals(account))
                    .filter(l -> l.getJournalEntry().getStatus() == JournalEntryStatus.POSTED)
                    .filter(l -> !l.getJournalEntry().getTransactionDate().isBefore(from))
                    .filter(l -> !l.getJournalEntry().getTransactionDate().isAfter(to))
                    .toList();
        }

        private List<JournalEntryLine> ofEntries(UUID account, Collection<UUID> entryIds) {
            return lines.stream()
                    .filter(l -> l.getGlAccountId().equals(account))
                    .filter(l -> entryIds.contains(l.getJournalEntry().getJournalEntryId()))
                    .toList();
        }

        private BigDecimal balance(UUID account, LocalDateTime asOf) {
            return lines.stream()
                    .filter(l -> l.getGlAccountId().equals(account))
                    .filter(l -> l.getJournalEntry().getStatus() == JournalEntryStatus.POSTED
                            || l.getJournalEntry().getStatus() == JournalEntryStatus.REVERSED)
                    .filter(l -> !l.getJournalEntry().getTransactionDate().isAfter(asOf))
                    .map(l -> l.getDebitAmount().subtract(l.getCreditAmount()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }

        private List<JournalEntryLine> byIds(Collection<UUID> ids) {
            return lines.stream().filter(l -> ids.contains(l.getLineId())).toList();
        }
    }
}
