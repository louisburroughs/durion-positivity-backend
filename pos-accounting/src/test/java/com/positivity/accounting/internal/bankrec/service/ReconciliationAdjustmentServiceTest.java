package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.STATEMENT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.snapshot;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.statement;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.terms;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.transaction;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.usd;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.AdjustmentReverseRequest;
import com.positivity.accounting.internal.bankrec.dto.BankReconciliationAdjustmentResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationAdjustmentRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationBankMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationGlMatch;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.AdjustmentStatus;
import com.positivity.accounting.internal.bankrec.enums.BankAdjustmentType;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationAdjustmentRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.LedgerMember;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.exception.AdjustmentSignInvalidException;
import com.positivity.accounting.internal.exception.GLAccountNotActiveException;
import com.positivity.accounting.internal.exception.MatchAmountMismatchException;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.service.AccountingPeriodGate;
import com.positivity.accounting.internal.service.GLMappingResolver;
import com.positivity.accounting.internal.service.IdempotencyService;
import com.positivity.accounting.internal.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ReconciliationAdjustmentServiceImpl} (SPEC §3.5, §4.2, §4.6, §4.7, §4.9, §8.2, §8.3; story S4, #2303,
 * criteria 9–13, 15): idempotency, the link rule, the authority, the server-computed residual and bridge,
 * {@code TRANSFER} validation and posting, the date rule and reversal.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationAdjustmentServiceImpl (#2303)")
class ReconciliationAdjustmentServiceTest {

    private static final String WHY = "Bank debit pending classification";
    private static final UUID CLEARING = UUID.fromString("5eed0acc-0000-4000-8000-000000002360");
    private static final UUID FEE_EXPENSE = UUID.fromString("5eed0acc-0000-4000-8000-000000006030");
    private static final UUID OTHER_BANK = UUID.fromString("5eed0acc-0000-4000-8000-000000001010");

    @Mock
    private ReconciliationSupport support;

    @Mock
    private ReconciliationEligibility eligibility;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private ReconciliationLedger ledger;

    @Mock
    private MatchWriter writer;

    @Mock
    private BankReconciliationAdjustmentRepository adjustments;

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private GLAccountRepository glAccounts;

    @Mock
    private GLMappingResolver glMappingResolver;

    @Mock
    private JournalEntryService journalEntryService;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private AccountingPeriodGate periodGate;

    @Mock
    private BankRecPolicy policy;

    @Mock
    private BankRecAuditRecorder audit;

    private ReconciliationAdjustmentServiceImpl service;
    private BankReconciliation recon;
    private final UUID journalEntryId = UUID.randomUUID();
    private final UUID cashLineId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ReconciliationAdjustmentServiceImpl(
                support,
                eligibility,
                calculator,
                ledger,
                writer,
                adjustments,
                matches,
                glMatches,
                bankMatches,
                transactions,
                statements,
                glAccounts,
                glMappingResolver,
                journalEntryService,
                idempotencyService,
                periodGate,
                policy,
                audit,
                usd());
        recon = reconciliation();
        lenient().when(support.require(RECON_ID)).thenReturn(recon);
        lenient().when(support.requireOpen(RECON_ID)).thenReturn(recon);
        lenient().when(support.currentUser()).thenReturn("preparer");
        lenient().when(support.now()).thenReturn(Instant.parse("2026-10-05T12:00:00Z"));
        lenient().when(adjustments.findByRequestId(any())).thenReturn(Optional.empty());
        lenient().when(adjustments.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(policy.otherApprovalThreshold()).thenReturn(Optional.empty());
        lenient()
                .when(glMappingResolver.resolveGLAccount(eq("BANK_RECONCILIATION"), eq("OTHER"), any()))
                .thenReturn(CLEARING);
        lenient()
                .when(glMappingResolver.resolveGLAccount(eq("BANK_RECONCILIATION"), eq("BANK_FEE"), any()))
                .thenReturn(FEE_EXPENSE);
        JournalEntryResponse created =
                JournalEntryResponse.builder().journalEntryId(journalEntryId).build();
        lenient().when(journalEntryService.createJournalEntry(any())).thenReturn(created);
        lenient()
                .when(journalEntryService.postJournalEntry(eq(journalEntryId), any()))
                .thenReturn(JournalEntryResponse.builder()
                        .journalEntryId(journalEntryId)
                        .entryNumber("JE-202609-0042")
                        .build());
        lenient()
                .when(ledger.linesOfEntries(eq(ACCOUNT_ID), anyCollection()))
                .thenAnswer(inv -> Map.of(
                        journalEntryId,
                        List.of(new LedgerLine(
                                cashLineId,
                                journalEntryId,
                                "JE-202609-0042",
                                END,
                                BigDecimal.ONE,
                                null,
                                null,
                                false))));
        lenient()
                .when(writer.create(any(), any(), anyList(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    BankReconciliationMatch match = new BankReconciliationMatch();
                    match.setMatchId(UUID.randomUUID());
                    return match;
                });
    }

    private static ReconciliationAdjustmentRequest.ReconciliationAdjustmentRequestBuilder request(
            BankAdjustmentType type, String amount) {
        return ReconciliationAdjustmentRequest.builder()
                .type(type)
                .amount(amount == null ? null : new BigDecimal(amount))
                .requestId(UUID.randomUUID());
    }

    private void approver(boolean approve) {
        lenient()
                .when(support.hasAuthority("accounting:reconciliation:approve"))
                .thenReturn(approve);
    }

    @Nested
    @DisplayName("idempotency (criterion 9)")
    class Idempotency {

        @Test
        @DisplayName(
                "the entry's sourceEventId is nameUUIDFromBytes of the adjustment id, and the key is registered [M]")
        void deterministicSourceEvent() {
            BankReconciliationAdjustmentResponse response = service.addAdjustment(
                    RECON_ID, request(BankAdjustmentType.BANK_FEE, "-15.00").build());

            ArgumentCaptor<JournalEntryCreateRequest> je = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntryService).createJournalEntry(je.capture());
            assertThat(je.getValue().getSourceEventId())
                    .isEqualTo(ReconciliationAdjustmentServiceImpl.sourceEventId(response.getAdjustmentId()));
            assertThat(ReconciliationAdjustmentServiceImpl.sourceEventId(response.getAdjustmentId()))
                    .as("deterministic")
                    .isEqualTo(ReconciliationAdjustmentServiceImpl.sourceEventId(response.getAdjustmentId()));
            verify(idempotencyService)
                    .registerKey("BANK_RECONCILIATION_ADJUSTMENT:" + response.getAdjustmentId(), journalEntryId);
            assertThat(response.getEntryNumber()).isEqualTo("JE-202609-0042");
            assertThat(response.getPostedPeriodCode()).isEqualTo("2026-09");
        }

        @Test
        @DisplayName("the same requestId replays without a second entry; another payload is IDEMPOTENCY_CONFLICT")
        void replay() {
            UUID requestId = UUID.randomUUID();
            BankReconciliationAdjustment original = new BankReconciliationAdjustment();
            original.setAdjustmentId(UUID.randomUUID());
            original.setReconciliation(recon);
            original.setAdjustmentType(BankAdjustmentType.BANK_FEE);
            original.setAmount(new BigDecimal("-15.00"));
            original.setJournalEntryId(journalEntryId);
            original.setStatus(AdjustmentStatus.POSTED);
            when(adjustments.findByRequestId(requestId)).thenReturn(Optional.of(original));
            when(journalEntryService.getJournalEntry(journalEntryId))
                    .thenReturn(JournalEntryResponse.builder()
                            .entryNumber("JE-202609-0042")
                            .build());

            BankReconciliationAdjustmentResponse replayed = service.addAdjustment(
                    RECON_ID,
                    request(BankAdjustmentType.BANK_FEE, "-15.00")
                            .requestId(requestId)
                            .build());
            assertThat(replayed.isReplayed()).isTrue();
            assertThatThrownBy(() -> service.addAdjustment(
                            RECON_ID,
                            request(BankAdjustmentType.BANK_FEE, "-16.00")
                                    .requestId(requestId)
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT));
            verify(journalEntryService, never()).createJournalEntry(any());
        }
    }

    @Nested
    @DisplayName("OTHER link, justification and authority (criteria 10, 13)")
    class Other {

        @Test
        @DisplayName("an unlinked OTHER is ADJUSTMENT_LINK_REQUIRED and posts nothing [M]")
        void unlinkedOtherPostsNothing() {
            approver(true);
            assertThatThrownBy(() -> service.addAdjustment(
                            RECON_ID,
                            request(BankAdjustmentType.OTHER, "5.00")
                                    .justification(WHY)
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_REQUIRED));
            verify(journalEntryService, never()).createJournalEntry(any());
        }

        @Test
        @DisplayName("a 9-character or blank OTHER justification is JUSTIFICATION_REQUIRED")
        void shortJustification() {
            BankTransaction bank = transaction("5.00", LocalDate.of(2026, 9, 10));
            for (String justification : new String[] {"123456789", "   ", null}) {
                assertThatThrownBy(() -> service.addAdjustment(
                                RECON_ID,
                                request(BankAdjustmentType.OTHER, "5.00")
                                        .bankTransactionId(bank.getBankTransactionId())
                                        .justification(justification)
                                        .build()))
                        .isInstanceOfSatisfying(
                                BankRecException.class,
                                e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
            }
        }

        @Test
        @DisplayName("with the threshold unset, an OTHER of 5.00 by adjust alone is 403; with approve it posts [M]")
        void authority() {
            BankTransaction bank = transaction("5.00", LocalDate.of(2026, 9, 10));
            when(eligibility.lockBankForMatch(eq(recon), anyCollection(), isNull()))
                    .thenReturn(List.of(bank));
            ReconciliationAdjustmentRequest linked = request(BankAdjustmentType.OTHER, "5.00")
                    .bankTransactionId(bank.getBankTransactionId())
                    .justification(WHY)
                    .build();
            approver(false);
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, linked))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code())
                                    .isEqualTo(BankRecErrorCode.RECONCILIATION_ADJUSTMENT_APPROVAL_REQUIRED));
            verify(journalEntryService, never()).createJournalEntry(any());

            approver(true);
            BankReconciliationAdjustmentResponse posted = service.addAdjustment(RECON_ID, linked);
            assertThat(posted.getBankTransactionId()).isEqualTo(bank.getBankTransactionId());
            ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
            verify(writer).create(eq(recon), header.capture(), eq(List.of(bank)), anyList(), anyString());
            assertThat(header.getValue().kind()).isEqualTo(MatchKind.ADJUSTMENT);
            assertThat(header.getValue().state()).isEqualTo(MatchState.ACCEPTED);
            assertThat(posted.getMatchId()).isNotNull();
        }

        @Test
        @DisplayName("a linked adjustment must explain its bank transaction within one cent")
        void linkedAmountMustAgree() {
            BankTransaction bank = transaction("-15.00", LocalDate.of(2026, 9, 10));
            when(eligibility.lockBankForMatch(eq(recon), anyCollection(), isNull()))
                    .thenReturn(List.of(bank));
            assertThatThrownBy(() -> service.addAdjustment(
                            RECON_ID,
                            request(BankAdjustmentType.BANK_FEE, "-12.00")
                                    .bankTransactionId(bank.getBankTransactionId())
                                    .build()))
                    .isInstanceOf(MatchAmountMismatchException.class);
        }
    }

    @Nested
    @DisplayName("residual settlement (criterion 11)")
    class Residual {

        private BankReconciliationMatch settled;
        private BankTransaction bank;

        @BeforeEach
        void match() {
            settled = new BankReconciliationMatch();
            settled.setMatchId(UUID.randomUUID());
            settled.setReconciliationId(RECON_ID);
            settled.setState(MatchState.ACCEPTED);
            settled.setMatchKind(MatchKind.MANY_TO_ONE);
            settled.setBankTotal(new BigDecimal("99.99"));
            settled.setLedgerTotal(new BigDecimal("100.00"));
            settled.setToleranceUsed(new BigDecimal("0.01"));
            bank = transaction("99.99", LocalDate.of(2026, 9, 14));
            lenient()
                    .when(matches.findByMatchIdAndReconciliationId(settled.getMatchId(), RECON_ID))
                    .thenReturn(Optional.of(settled));
            lenient()
                    .when(bankMatches.findByMatchIdAndActiveTrue(settled.getMatchId()))
                    .thenReturn(List.of(
                            new BankReconciliationBankMatch(settled.getMatchId(), bank.getBankTransactionId())));
            lenient().when(transactions.findAllById(anyList())).thenReturn(List.of(bank));
            BankReconciliationGlMatch member = new BankReconciliationGlMatch();
            member.setGlLineId(UUID.randomUUID());
            member.setSignedAmount(new BigDecimal("100.00"));
            lenient()
                    .when(glMatches.findByMatchIdAndActiveTrue(settled.getMatchId()))
                    .thenReturn(List.of(member));
        }

        @Test
        @DisplayName("posts exactly the served residual by adjust alone and replaces the match, same kind [M]")
        void settlesTheResidual() {
            approver(false);
            BankReconciliationAdjustmentResponse posted = service.addAdjustment(
                    RECON_ID,
                    request(BankAdjustmentType.OTHER, null)
                            .settlesMatchId(settled.getMatchId())
                            .justification(WHY)
                            .build());

            assertThat(posted.getAmount()).isEqualByComparingTo("-0.01");
            assertThat(posted.getTransactionDate())
                    .as("the latest bank member's date")
                    .isEqualTo(LocalDate.of(2026, 9, 14));
            verify(writer).end(settled, MatchState.UNMATCHED, "RESIDUAL_SETTLED", "preparer");
            ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<LedgerMember>> members = ArgumentCaptor.forClass(List.class);
            verify(writer).create(eq(recon), header.capture(), eq(List.of(bank)), members.capture(), anyString());
            assertThat(header.getValue().kind()).isEqualTo(MatchKind.MANY_TO_ONE);
            assertThat(header.getValue().replacesMatchId()).isEqualTo(settled.getMatchId());
            assertThat(members.getValue())
                    .extracting(LedgerMember::glLineId)
                    .contains(cashLineId)
                    .hasSize(2);
        }

        @Test
        @DisplayName("an exact match or a sent amount other than the residual is ADJUSTMENT_LINK_NOT_ELIGIBLE")
        void notEligible() {
            assertThatThrownBy(() -> service.addAdjustment(
                            RECON_ID,
                            request(BankAdjustmentType.OTHER, "-0.02")
                                    .settlesMatchId(settled.getMatchId())
                                    .justification(WHY)
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
            settled.setToleranceUsed(BigDecimal.ZERO);
            assertThatThrownBy(() -> service.addAdjustment(
                            RECON_ID,
                            request(BankAdjustmentType.OTHER, null)
                                    .settlesMatchId(settled.getMatchId())
                                    .justification(WHY)
                                    .build()))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
        }
    }

    @Nested
    @DisplayName("gap bridge (criterion 12)")
    class Bridge {

        @BeforeEach
        void acknowledged() {
            approver(true);
            lenient()
                    .when(statements.findById(STATEMENT_ID))
                    .thenReturn(Optional.of(statement(STATEMENT_ID, START, END, "Changed banks in August")));
            lenient().when(calculator.compute(recon)).thenReturn(snapshot(terms("-45.67", "-45.67")));
        }

        private ReconciliationAdjustmentRequest bridge(String amount) {
            return request(BankAdjustmentType.OTHER, amount)
                    .bridgesStatementId(STATEMENT_ID)
                    .justification("Gap left by the change of bank")
                    .build();
        }

        @Test
        @DisplayName("posts the opening difference, Dr clearing / Cr cash, dated the day before the window")
        void postsTheOpeningDifference() {
            BankReconciliationAdjustmentResponse posted = service.addAdjustment(RECON_ID, bridge(null));

            assertThat(posted.getAmount()).isEqualByComparingTo("-45.67");
            assertThat(posted.getTransactionDate()).isEqualTo(START.minusDays(1));
            ArgumentCaptor<JournalEntryCreateRequest> je = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntryService).createJournalEntry(je.capture());
            assertThat(je.getValue().getLines())
                    .satisfiesExactly(
                            clearing -> {
                                assertThat(clearing.getGlAccountId()).isEqualTo(CLEARING);
                                assertThat(clearing.getDebitAmount()).isEqualByComparingTo("45.67");
                            },
                            cash -> {
                                assertThat(cash.getGlAccountId()).isEqualTo(ACCOUNT_ID);
                                assertThat(cash.getCreditAmount()).isEqualByComparingTo("45.67");
                            });
        }

        @Test
        @DisplayName("the earliest OPEN period takes the bridge when the day before the window is closed")
        void closedPeriodDatesTheBridgeLater() {
            when(periodGate.isPostingBlocked(START.minusDays(1))).thenReturn(true);
            ReconciliationAdjustmentRequest request = bridge(null);
            request.setTransactionDate(LocalDate.of(2026, 10, 1));
            assertThat(service.addAdjustment(RECON_ID, request).getTransactionDate())
                    .isEqualTo(LocalDate.of(2026, 10, 1));
        }

        @Test
        @DisplayName(
                "−45.00 instead of −45.67, no acknowledgement, or a small difference is NOT_ELIGIBLE; a second bridge is 409")
        void refusals() {
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, bridge("-45.00")))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
            when(adjustments.existsByBridgesStatementIdAndStatus(STATEMENT_ID, AdjustmentStatus.POSTED))
                    .thenReturn(true);
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, bridge(null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_BRIDGE_ALREADY_POSTED));
            when(adjustments.existsByBridgesStatementIdAndStatus(STATEMENT_ID, AdjustmentStatus.POSTED))
                    .thenReturn(false);
            when(calculator.compute(recon)).thenReturn(snapshot(terms("0.01", "0.01")));
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, bridge(null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
            when(statements.findById(STATEMENT_ID)).thenReturn(Optional.of(statement(STATEMENT_ID, START, END, null)));
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, bridge(null)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
            verify(journalEntryService, never()).createJournalEntry(any());
        }
    }

    @Nested
    @DisplayName("TRANSFER (criterion 15)")
    class Transfer {

        private GLAccount counter;

        @BeforeEach
        void counterAccount() {
            counter = new GLAccount(OTHER_BANK);
            counter.setAccountCode("1010");
            counter.setReconcilable(true);
            counter.setAccountSubtype(AccountSubtype.BANK_CASH);
            counter.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
            lenient().when(glAccounts.findById(OTHER_BANK)).thenReturn(Optional.of(counter));
        }

        private ReconciliationAdjustmentRequest transfer(String amount, UUID counterId) {
            return request(BankAdjustmentType.TRANSFER, amount)
                    .counterGlAccountId(counterId)
                    .build();
        }

        @Test
        @DisplayName("+250.00 posts Dr reconciled / Cr counter with no mapping lookup")
        void postsAgainstTheCounterAccount() {
            service.addAdjustment(RECON_ID, transfer("250.00", OTHER_BANK));
            ArgumentCaptor<JournalEntryCreateRequest> je = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
            verify(journalEntryService).createJournalEntry(je.capture());
            assertThat(je.getValue().getLines())
                    .satisfiesExactly(
                            cash -> {
                                assertThat(cash.getGlAccountId()).isEqualTo(ACCOUNT_ID);
                                assertThat(cash.getDebitAmount()).isEqualByComparingTo("250.00");
                            },
                            other -> {
                                assertThat(other.getGlAccountId()).isEqualTo(OTHER_BANK);
                                assertThat(other.getCreditAmount()).isEqualByComparingTo("250.00");
                            });
            verify(glMappingResolver, never()).resolveGLAccount(any(), any(), any());
        }

        @Test
        @DisplayName("the counter rules in order: unknown or self, inactive, not a bank account; zero is sign-invalid")
        void validationOrder() {
            when(glAccounts.findById(ACCOUNT_ID)).thenReturn(Optional.of(new GLAccount(ACCOUNT_ID)));
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, transfer("250.00", ACCOUNT_ID)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));
            UUID unknown = UUID.randomUUID();
            when(glAccounts.findById(unknown)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, transfer("250.00", unknown)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_LINK_NOT_ELIGIBLE));

            counter.setDeactivationDate(LocalDateTime.of(2021, 1, 1, 0, 0));
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, transfer("250.00", OTHER_BANK)))
                    .isInstanceOf(GLAccountNotActiveException.class);
            counter.setDeactivationDate(null);
            counter.setAccountSubtype(AccountSubtype.UNDEPOSITED_FUNDS);
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, transfer("250.00", OTHER_BANK)))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ACCOUNT_NOT_RECONCILABLE));
            counter.setAccountSubtype(AccountSubtype.BANK_CASH);
            assertThatThrownBy(() -> service.addAdjustment(RECON_ID, transfer("0", OTHER_BANK)))
                    .isInstanceOf(AdjustmentSignInvalidException.class);
            verify(journalEntryService, never()).createJournalEntry(any());
        }
    }

    @Nested
    @DisplayName("reverse (§4.9 path 2)")
    class Reverse {

        @Test
        @DisplayName("reverses the entry, unmatches the ADJUSTMENT match, and refuses a second reversal")
        void reverse() {
            BankReconciliationAdjustment fee = new BankReconciliationAdjustment();
            fee.setAdjustmentId(UUID.randomUUID());
            fee.setReconciliation(recon);
            fee.setAdjustmentType(BankAdjustmentType.BANK_FEE);
            fee.setAmount(new BigDecimal("-15.00"));
            fee.setJournalEntryId(journalEntryId);
            fee.setStatus(AdjustmentStatus.POSTED);
            when(adjustments.findByAdjustmentIdAndReconciliation_ReconciliationId(fee.getAdjustmentId(), RECON_ID))
                    .thenReturn(Optional.of(fee));
            UUID reversalId = UUID.randomUUID();
            when(journalEntryService.reverseJournalEntry(eq(journalEntryId), anyString(), isNull(), isNull()))
                    .thenReturn(JournalEntryResponse.builder()
                            .journalEntryId(reversalId)
                            .build());
            BankReconciliationGlMatch member = new BankReconciliationGlMatch();
            member.setMatchId(UUID.randomUUID());
            when(glMatches.findByGlLineIdInAndActiveTrue(List.of(cashLineId))).thenReturn(List.of(member));
            BankReconciliationMatch adjustmentMatch = new BankReconciliationMatch();
            adjustmentMatch.setMatchKind(MatchKind.ADJUSTMENT);
            adjustmentMatch.setState(MatchState.ACCEPTED);
            when(matches.findById(member.getMatchId())).thenReturn(Optional.of(adjustmentMatch));

            AdjustmentReverseRequest request = new AdjustmentReverseRequest("Bank refunded the fee", null, null);
            BankReconciliationAdjustmentResponse reversed = service.reverse(RECON_ID, fee.getAdjustmentId(), request);

            assertThat(reversed.getStatus()).isEqualTo(AdjustmentStatus.REVERSED);
            assertThat(reversed.getReversalJournalEntryId()).isEqualTo(reversalId);
            verify(writer).end(eq(adjustmentMatch), eq(MatchState.UNMATCHED), anyString(), eq("preparer"));
            assertThatThrownBy(() -> service.reverse(RECON_ID, fee.getAdjustmentId(), request))
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.ADJUSTMENT_ALREADY_REVERSED));
        }
    }
}
