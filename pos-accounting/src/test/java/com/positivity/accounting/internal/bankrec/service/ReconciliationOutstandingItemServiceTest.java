package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.item;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.postedLine;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.OutstandingItemJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemRegisterRequest;
import com.positivity.accounting.internal.bankrec.dto.OutstandingItemResponse;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemKind;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import com.positivity.accounting.internal.bankrec.enums.ReconciliationStatus;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationOutstandingItemRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Outstanding items (SPEC §3.6, §8.3; story S4, #2303, criteria 10, 14): eligibility and sign, the
 * justification rule, reaffirmation of aged timing items and clear-in-gap.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationOutstandingItemServiceImpl (#2303)")
class ReconciliationOutstandingItemServiceTest {

    private static final String WHY = "Deposit made after the bank's cut-off";

    @Mock
    private ReconciliationSupport support;

    @Mock
    private ReconciliationEligibility eligibility;

    @Mock
    private BankReconciliationOutstandingItemRepository items;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankReconciliationRepository reconciliations;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankRecAuditRecorder audit;

    private ReconciliationOutstandingItemServiceImpl service;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        BankRecSettings settings = BankRecSettings.defaults();
        service = new ReconciliationOutstandingItemServiceImpl(
                support,
                eligibility,
                new ReconciliationCalculator(null, null, null, null, null, null, settings),
                items,
                glMatches,
                bankMatches,
                reconciliations,
                statements,
                audit,
                settings);
        recon = reconciliation();
        lenient().when(support.requireOpen(RECON_ID)).thenReturn(recon);
        lenient().when(support.require(RECON_ID)).thenReturn(recon);
        lenient().when(support.currentUser()).thenReturn("preparer");
        lenient().when(support.now()).thenReturn(Instant.parse("2026-10-05T12:00:00Z"));
        lenient().when(support.today()).thenReturn(LocalDate.of(2026, 10, 5));
        lenient().when(items.saveAndFlush(any())).thenAnswer(inv -> {
            BankReconciliationOutstandingItem saved = inv.getArgument(0);
            saved.setOutstandingItemId(UUID.randomUUID());
            return saved;
        });
    }

    private JournalEntryLine ledgerLine(String amount, LocalDate date) {
        JournalEntryLine line = postedLine(amount, date);
        lenient()
                .when(eligibility.lockLedger(eq(recon), eq(List.of(line.getLineId()))))
                .thenReturn(List.of(line));
        return line;
    }

    private static OutstandingItemRegisterRequest onLine(JournalEntryLine line, OutstandingItemKind kind, String why) {
        return OutstandingItemRegisterRequest.builder()
                .glLineId(line.getLineId())
                .itemKind(kind)
                .justification(why)
                .build();
    }

    @Nested
    @DisplayName("register")
    class Register {

        @Test
        @DisplayName("a deposit in transit copies the line's amount and date, registers OPEN, and posts nothing")
        void depositInTransit() {
            JournalEntryLine line = ledgerLine("500.00", LocalDate.of(2026, 9, 30));

            OutstandingItemResponse response =
                    service.register(RECON_ID, onLine(line, OutstandingItemKind.DEPOSIT_IN_TRANSIT, null));

            assertThat(response.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
            assertThat(response.getSide()).isEqualTo(OutstandingItemSide.LEDGER);
            assertThat(response.getSignedAmount()).isEqualByComparingTo("500.00");
            assertThat(response.getItemDate()).isEqualTo(LocalDate.of(2026, 9, 30));
            assertThat(response.getRegisteredInReconciliationId()).isEqualTo(RECON_ID);
            verify(support).refresh(recon);
        }

        @Test
        @DisplayName("#2572: a bank opening's item line registers with the item's own date, not the cutover date the"
                + " entry is dated on; an aged one then needs a justification")
        void openingItemKeepsItsOwnDate() {
            JournalEntryLine check = ledgerLine("-450.00", LocalDate.of(2026, 9, 30));
            check.setDimensions(java.util.Map.of(
                    "outstandingItemType", "OUTSTANDING_CHECK", "reference", "1043", "itemDate", "2026-09-28"));

            assertThat(service.register(RECON_ID, onLine(check, OutstandingItemKind.OUTSTANDING_CHECK, null))
                            .getItemDate())
                    .isEqualTo(LocalDate.of(2026, 9, 28));

            JournalEntryLine aged = ledgerLine("-75.00", LocalDate.of(2026, 9, 30));
            aged.setDimensions(java.util.Map.of(
                    "outstandingItemType", "OUTSTANDING_CHECK", "reference", "0991", "itemDate", "2026-05-01"));
            assertThatThrownBy(
                            () -> service.register(RECON_ID, onLine(aged, OutstandingItemKind.OUTSTANDING_CHECK, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));

            // A date that is not an opening item's, or one after the entry's, leaves the entry's date.
            JournalEntryLine other = ledgerLine("60.00", LocalDate.of(2026, 9, 30));
            other.setDimensions(java.util.Map.of("itemDate", "2026-09-01"));
            assertThat(service.register(RECON_ID, onLine(other, OutstandingItemKind.DEPOSIT_IN_TRANSIT, null))
                            .getItemDate())
                    .isEqualTo(LocalDate.of(2026, 9, 30));
        }

        @Test
        @DisplayName("an OUTSTANDING_CHECK on a debit, or a DEPOSIT_IN_TRANSIT on a credit, is not eligible (§8.3)")
        void signMustFitTheKind() {
            JournalEntryLine debit = ledgerLine("200.00", LocalDate.of(2026, 9, 20));
            assertThatThrownBy(() ->
                            service.register(RECON_ID, onLine(debit, OutstandingItemKind.OUTSTANDING_CHECK, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));
            JournalEntryLine credit = ledgerLine("-200.00", LocalDate.of(2026, 9, 20));
            assertThatThrownBy(() ->
                            service.register(RECON_ID, onLine(credit, OutstandingItemKind.DEPOSIT_IN_TRANSIT, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));
            verify(items, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("a matched line or a line dated after the window end is not eligible (O1)")
        void matchedOrLaterLineNotEligible() {
            JournalEntryLine matched = ledgerLine("-80.00", LocalDate.of(2026, 9, 20));
            when(glMatches.findByGlLineIdInAndActiveTrue(List.of(matched.getLineId())))
                    .thenReturn(List.of(new BankReconciliationGlMatch()));
            assertThatThrownBy(() ->
                            service.register(RECON_ID, onLine(matched, OutstandingItemKind.OUTSTANDING_CHECK, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));

            JournalEntryLine october = ledgerLine("-80.00", LocalDate.of(2026, 10, 2));
            assertThatThrownBy(() ->
                            service.register(RECON_ID, onLine(october, OutstandingItemKind.OUTSTANDING_CHECK, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("OTHER_LEDGER_TIMING and an item older than the aging days need a justification")
        void justificationRule() {
            JournalEntryLine timing = ledgerLine("30.00", LocalDate.of(2026, 9, 15));
            assertThatThrownBy(() -> service.register(
                            RECON_ID, onLine(timing, OutstandingItemKind.OTHER_LEDGER_TIMING, "too short")))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));

            JournalEntryLine old = ledgerLine("45.00", LocalDate.of(2026, 5, 1));
            assertThatThrownBy(
                            () -> service.register(RECON_ID, onLine(old, OutstandingItemKind.DEPOSIT_IN_TRANSIT, null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
            assertThat(service.register(RECON_ID, onLine(old, OutstandingItemKind.DEPOSIT_IN_TRANSIT, WHY))
                            .getJustification())
                    .isEqualTo(WHY);
        }

        @Test
        @DisplayName("a ledger kind on a bank transaction, or both ids, is refused")
        void sideMustFit() {
            OutstandingItemRegisterRequest wrongSide = OutstandingItemRegisterRequest.builder()
                    .bankTransactionId(UUID.randomUUID())
                    .itemKind(OutstandingItemKind.DEPOSIT_IN_TRANSIT)
                    .build();
            assertThatThrownBy(() -> service.register(RECON_ID, wrongSide))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));
            OutstandingItemRegisterRequest both = OutstandingItemRegisterRequest.builder()
                    .bankTransactionId(UUID.randomUUID())
                    .glLineId(UUID.randomUUID())
                    .itemKind(OutstandingItemKind.BANK_ERROR_PENDING)
                    .build();
            assertThatThrownBy(() -> service.register(RECON_ID, both))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.VALIDATION_ERROR));
        }
    }

    @Nested
    @DisplayName("reaffirm, release, clear-in-gap")
    class Lifecycle {

        @Test
        @DisplayName(
                "reaffirm needs an aged OPEN OTHER_LEDGER_TIMING item and records this reconciliation (criterion 14)")
        void reaffirm() {
            BankReconciliationOutstandingItem fresh = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.OTHER_LEDGER_TIMING,
                    "30",
                    LocalDate.of(2026, 9, 1));
            when(items.findByOutstandingItemIdAndGlAccountId(eq(fresh.getOutstandingItemId()), any()))
                    .thenReturn(Optional.of(fresh));
            assertThatThrownBy(() -> service.reaffirm(
                            RECON_ID, fresh.getOutstandingItemId(), new OutstandingItemJustificationRequest(WHY)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));

            fresh.setItemDate(LocalDate.of(2026, 5, 1));
            OutstandingItemResponse response = service.reaffirm(
                    RECON_ID, fresh.getOutstandingItemId(), new OutstandingItemJustificationRequest(WHY));
            assertThat(response.getLastReaffirmedInReconciliationId()).isEqualTo(RECON_ID);
            assertThat(response.getReaffirmJustification()).isEqualTo(WHY);
            assertThat(response.getStatus()).isEqualTo(OutstandingItemStatus.OPEN);
        }

        @Test
        @DisplayName("release is refused once the registering reconciliation is FINALIZED")
        void releaseAfterFinalize() {
            BankReconciliationOutstandingItem open = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.DEPOSIT_IN_TRANSIT,
                    "30",
                    LocalDate.of(2026, 8, 30));
            UUID earlier = UUID.randomUUID();
            open.setRegisteredInReconciliationId(earlier);
            when(items.findByOutstandingItemIdAndGlAccountId(eq(open.getOutstandingItemId()), any()))
                    .thenReturn(Optional.of(open));
            BankReconciliation finalized = reconciliation();
            finalized.setStatus(ReconciliationStatus.FINALIZED);
            when(reconciliations.findById(earlier)).thenReturn(Optional.of(finalized));

            assertThatThrownBy(() -> service.release(
                            RECON_ID,
                            open.getOutstandingItemId(),
                            new OutstandingItemReasonRequest("Registered twice by mistake")))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));

            finalized.setStatus(ReconciliationStatus.IN_PROGRESS);
            assertThat(service.release(
                                    RECON_ID,
                                    open.getOutstandingItemId(),
                                    new OutstandingItemReasonRequest("Registered twice by mistake"))
                            .getStatus())
                    .isEqualTo(OutstandingItemStatus.RELEASED);
        }

        @Test
        @DisplayName("clear-in-gap closes an earlier item on the day before an acknowledged statement (criterion 14)")
        void clearInGap() {
            BankReconciliationOutstandingItem earlier = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.OUTSTANDING_CHECK,
                    "-40",
                    LocalDate.of(2026, 7, 20));
            earlier.setRegisteredInReconciliationId(UUID.randomUUID());
            when(items.findByOutstandingItemIdAndGlAccountId(eq(earlier.getOutstandingItemId()), any()))
                    .thenReturn(Optional.of(earlier));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));

            assertThatThrownBy(() -> service.clearInGap(
                            RECON_ID, earlier.getOutstandingItemId(), new OutstandingItemJustificationRequest(WHY)))
                    .as("the statement carries no acknowledgement")
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));

            when(statements.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, "Changed banks in August")));
            OutstandingItemResponse response = service.clearInGap(
                    RECON_ID, earlier.getOutstandingItemId(), new OutstandingItemJustificationRequest(WHY));
            assertThat(response.getStatus()).isEqualTo(OutstandingItemStatus.CLEARED_IN_GAP);
            assertThat(response.getClosedOn()).isEqualTo(START.minusDays(1));
            assertThat(response.getClearedInReconciliationId()).isEqualTo(RECON_ID);
        }

        @Test
        @DisplayName("clear-in-gap refuses an item registered in this reconciliation")
        void clearInGapRefusesOwnItem() {
            BankReconciliationOutstandingItem own = item(
                    OutstandingItemSide.LEDGER,
                    OutstandingItemKind.OUTSTANDING_CHECK,
                    "-40",
                    LocalDate.of(2026, 7, 20));
            when(items.findByOutstandingItemIdAndGlAccountId(eq(own.getOutstandingItemId()), any()))
                    .thenReturn(Optional.of(own));
            when(statements.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, "Changed banks in August")));
            assertThatThrownBy(() -> service.clearInGap(
                            RECON_ID, own.getOutstandingItemId(), new OutstandingItemJustificationRequest(WHY)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.OUTSTANDING_ITEM_NOT_ELIGIBLE));
        }
    }
}
