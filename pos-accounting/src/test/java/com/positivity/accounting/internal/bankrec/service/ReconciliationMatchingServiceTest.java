package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.postedLine;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.snapshot;
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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.dto.AutoMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationMatch;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.MatchKind;
import com.positivity.accounting.internal.bankrec.enums.MatchOrigin;
import com.positivity.accounting.internal.bankrec.enums.MatchState;
import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationBankMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationGlMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankReconciliationMatchRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.CandidateFinder.ScoredLine;
import com.positivity.accounting.internal.bankrec.service.CandidateScorer.Score;
import com.positivity.accounting.internal.bankrec.service.MatchWriter.Header;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.repository.JournalEntryLineRepository;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link ReconciliationMatchingServiceImpl} (SPEC §3.4, §4.6, §8.3; story S4, #2303, criteria 7, 8): the M5
 * review gate, cardinality before any lookup, replay, state checks, and auto-match proposing only (M6, D12).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("ReconciliationMatchingServiceImpl (#2303)")
class ReconciliationMatchingServiceTest {

    @Mock
    private ReconciliationSupport support;

    @Mock
    private ReconciliationEligibility eligibility;

    @Mock
    private ReconciliationCalculator calculator;

    @Mock
    private CandidateFinder finder;

    @Mock
    private MatchWriter writer;

    @Mock
    private MatchResponses responses;

    @Mock
    private BankReconciliationMatchRepository matches;

    @Mock
    private BankReconciliationGlMatchRepository glMatches;

    @Mock
    private BankReconciliationBankMatchRepository bankMatches;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private JournalEntryLineRepository lines;

    @Mock
    private BankRecAuditRecorder audit;

    private ReconciliationMatchingServiceImpl service;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        service = new ReconciliationMatchingServiceImpl(
                support,
                eligibility,
                calculator,
                finder,
                writer,
                responses,
                matches,
                glMatches,
                bankMatches,
                transactions,
                lines,
                audit,
                BankRecSettings.defaults(),
                usd());
        recon = reconciliation();
        lenient().when(support.requireOpen(RECON_ID)).thenReturn(recon);
        lenient().when(support.currentUser()).thenReturn("preparer");
        lenient().when(matches.findByRequestId(any())).thenReturn(Optional.empty());
        lenient()
                .when(writer.create(any(), any(), anyList(), anyList(), anyString()))
                .thenAnswer(inv -> {
                    Header header = inv.getArgument(1);
                    BankReconciliationMatch match = new BankReconciliationMatch();
                    match.setMatchId(UUID.randomUUID());
                    match.setReconciliationId(RECON_ID);
                    match.setState(header.state());
                    match.setOrigin(header.origin());
                    match.setMatchKind(header.kind());
                    return match;
                });
        lenient().when(responses.of(any(BankReconciliationMatch.class))).thenReturn(new ReconciliationMatchResponse());
    }

    private ReconciliationMatchCreateRequest request(List<UUID> bank, List<UUID> gl, String justification) {
        return ReconciliationMatchCreateRequest.builder()
                .bankTransactionIds(bank)
                .glLineIds(gl)
                .justification(justification)
                .requestId(UUID.randomUUID())
                .build();
    }

    @Test
    @DisplayName("1:N answers MATCH_REQUIRES_REVIEW until justified, then is created ACCEPTED (criterion 7) [M]")
    void oneToManyNeedsAJustification() {
        BankTransaction bank = transaction("1250.00", LocalDate.of(2026, 9, 12));
        JournalEntryLine first = postedLine("1000.00", LocalDate.of(2026, 9, 11));
        JournalEntryLine second = postedLine("250.00", LocalDate.of(2026, 9, 12));
        when(eligibility.lockBankForMatch(eq(recon), anyCollection(), isNull())).thenReturn(List.of(bank));
        when(eligibility.lockLedgerForMatch(eq(recon), anyCollection(), isNull()))
                .thenReturn(List.of(first, second));
        List<UUID> bankIds = List.of(bank.getBankTransactionId());
        List<UUID> glIds = List.of(first.getLineId(), second.getLineId());

        assertThatThrownBy(() -> service.createMatch(RECON_ID, request(bankIds, glIds, null)))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.MATCH_REQUIRES_REVIEW);
                    assertThat(e.fieldErrors()).containsEntry("justification", "CARDINALITY_NOT_ONE_TO_ONE");
                });
        verify(writer, never()).create(any(), any(), anyList(), anyList(), anyString());

        service.createMatch(RECON_ID, request(bankIds, glIds, "Batch deposit of two receipts"));

        ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
        verify(writer).create(eq(recon), header.capture(), eq(List.of(bank)), anyList(), eq("preparer"));
        assertThat(header.getValue().kind()).isEqualTo(MatchKind.ONE_TO_MANY);
        assertThat(header.getValue().state()).isEqualTo(MatchState.ACCEPTED);
        assertThat(header.getValue().origin()).isEqualTo(MatchOrigin.USER);
        assertThat(header.getValue().justification()).isEqualTo("Batch deposit of two receipts");
        verify(support).refresh(recon);
    }

    @Test
    @DisplayName("an exact 1:1 is ACCEPTED without a justification")
    void exactOneToOne() {
        BankTransaction bank = transaction("100.00", LocalDate.of(2026, 9, 12));
        JournalEntryLine line = postedLine("100.00", LocalDate.of(2026, 9, 12));
        when(eligibility.lockBankForMatch(eq(recon), anyCollection(), isNull())).thenReturn(List.of(bank));
        when(eligibility.lockLedgerForMatch(eq(recon), anyCollection(), isNull()))
                .thenReturn(List.of(line));

        service.createMatch(RECON_ID, request(List.of(bank.getBankTransactionId()), List.of(line.getLineId()), null));

        verify(writer).create(eq(recon), any(), anyList(), anyList(), anyString());
    }

    @Test
    @DisplayName("a 99.99 bank row against a 100.00 line needs a justification for the tolerance")
    void toleranceNeedsAJustification() {
        BankTransaction bank = transaction("99.99", LocalDate.of(2026, 9, 12));
        JournalEntryLine line = postedLine("100.00", LocalDate.of(2026, 9, 12));
        when(eligibility.lockBankForMatch(eq(recon), anyCollection(), isNull())).thenReturn(List.of(bank));
        when(eligibility.lockLedgerForMatch(eq(recon), anyCollection(), isNull()))
                .thenReturn(List.of(line));

        assertThatThrownBy(() -> service.createMatch(
                        RECON_ID, request(List.of(bank.getBankTransactionId()), List.of(line.getLineId()), null)))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.fieldErrors()).containsEntry("justification", "TOLERANCE_USED"));
    }

    @Test
    @DisplayName("N:M is refused before any row is read [M]")
    void manyToManyRefusedFirst() {
        List<UUID> two = List.of(UUID.randomUUID(), UUID.randomUUID());
        List<UUID> twoMore = List.of(UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> service.createMatch(RECON_ID, request(two, twoMore, "Split payout pairing")))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.MATCH_CARDINALITY_NOT_ALLOWED));
        verify(eligibility, never()).lockBankForMatch(any(), anyCollection(), any());
    }

    @Test
    @DisplayName("a replayed requestId with other members is IDEMPOTENCY_CONFLICT")
    void replayConflict() {
        UUID requestId = UUID.randomUUID();
        BankReconciliationMatch original = new BankReconciliationMatch();
        original.setReconciliationId(RECON_ID);
        original.setRequestId(requestId);
        when(matches.findByRequestId(requestId)).thenReturn(Optional.of(original));
        ReconciliationMatchResponse served = new ReconciliationMatchResponse();
        served.setBankTransactionIds(List.of(UUID.randomUUID()));
        served.setGlLineIds(List.of(UUID.randomUUID()));
        when(responses.of(original)).thenReturn(served);

        ReconciliationMatchCreateRequest same = request(served.getBankTransactionIds(), served.getGlLineIds(), null);
        same.setRequestId(requestId);
        assertThat(service.createMatch(RECON_ID, same).isReplayed()).isTrue();

        ReconciliationMatchCreateRequest other = request(List.of(UUID.randomUUID()), served.getGlLineIds(), null);
        other.setRequestId(requestId);
        assertThatThrownBy(() -> service.createMatch(RECON_ID, other))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT));
    }

    @Test
    @DisplayName("a replayed requestId with another justification is IDEMPOTENCY_CONFLICT; padding is not a change")
    void replayComparesTheJustification() {
        UUID requestId = UUID.randomUUID();
        BankReconciliationMatch original = new BankReconciliationMatch();
        original.setReconciliationId(RECON_ID);
        original.setRequestId(requestId);
        original.setJustification("Batch deposit of two receipts");
        when(matches.findByRequestId(requestId)).thenReturn(Optional.of(original));
        ReconciliationMatchResponse served = new ReconciliationMatchResponse();
        served.setBankTransactionIds(List.of(UUID.randomUUID()));
        served.setGlLineIds(List.of(UUID.randomUUID(), UUID.randomUUID()));
        when(responses.of(original)).thenReturn(served);

        ReconciliationMatchCreateRequest same =
                request(served.getBankTransactionIds(), served.getGlLineIds(), "  Batch deposit of two receipts ");
        same.setRequestId(requestId);
        assertThat(service.createMatch(RECON_ID, same).isReplayed()).isTrue();

        for (String justification : java.util.Arrays.asList("Another reason for the match", null)) {
            ReconciliationMatchCreateRequest other =
                    request(served.getBankTransactionIds(), served.getGlLineIds(), justification);
            other.setRequestId(requestId);
            assertThatThrownBy(() -> service.createMatch(RECON_ID, other))
                    .as("justification %s", justification)
                    .isInstanceOfSatisfying(
                            BankRecException.class,
                            e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.IDEMPOTENCY_CONFLICT));
        }
    }

    @Test
    @DisplayName("unmatch needs a reason and an ACCEPTED match; the header is kept UNMATCHED (criterion 8)")
    void unmatchRules() {
        UUID matchId = UUID.randomUUID();
        BankReconciliationMatch proposed = new BankReconciliationMatch();
        proposed.setMatchId(matchId);
        proposed.setState(MatchState.PROPOSED);
        when(matches.findByMatchIdAndReconciliationId(matchId, RECON_ID)).thenReturn(Optional.of(proposed));

        assertThatThrownBy(() -> service.unmatch(RECON_ID, matchId, new ReconciliationUnmatchRequest("short")))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));
        assertThatThrownBy(() -> service.unmatch(
                        RECON_ID, matchId, new ReconciliationUnmatchRequest("Paired the wrong deposit")))
                .isInstanceOfSatisfying(
                        BankRecException.class,
                        e -> assertThat(e.code()).isEqualTo(BankRecErrorCode.MATCH_STATE_INVALID));

        proposed.setState(MatchState.ACCEPTED);
        service.unmatch(RECON_ID, matchId, new ReconciliationUnmatchRequest("Paired the wrong deposit"));
        verify(writer).end(proposed, MatchState.UNMATCHED, "Paired the wrong deposit", "preparer");
        verify(audit)
                .record(
                        BankRecAuditRecorder.RECONCILIATION_MATCH,
                        matchId,
                        BankRecAuditRecorder.RECONCILIATION_UNMATCH,
                        "preparer",
                        "Paired the wrong deposit",
                        "ACCEPTED",
                        "UNMATCHED");
    }

    @Test
    @DisplayName("auto-match proposes a clear winner as PROPOSED RULE, never ACCEPTED; a tie proposes nothing [M]")
    void autoMatchProposesOnly() {
        BankTransaction clear = transaction("250.00", LocalDate.of(2026, 9, 12));
        BankTransaction tied = transaction("80.00", LocalDate.of(2026, 9, 14));
        when(calculator.compute(recon)).thenReturn(withUnexplainedBank(List.of(clear, tied)));
        when(bankMatches.findByBankTransactionIdInAndActiveTrue(anyCollection()))
                .thenReturn(List.of());
        LedgerLine winner = line("250.00", LocalDate.of(2026, 9, 12));
        LedgerLine runnerUp = line("250.00", LocalDate.of(2026, 9, 5));
        LedgerLine tieA = line("80.00", LocalDate.of(2026, 9, 14));
        LedgerLine tieB = line("80.00", LocalDate.of(2026, 9, 14));
        Map<UUID, List<ScoredLine>> ranked = new LinkedHashMap<>();
        ranked.put(clear.getBankTransactionId(), List.of(scored(winner, 110), scored(runnerUp, 80)));
        ranked.put(tied.getBankTransactionId(), List.of(scored(tieA, 100), scored(tieB, 100)));
        when(finder.rankedForEach(eq(recon), anyList())).thenReturn(ranked);

        AutoMatchResponse response = service.autoMatch(RECON_ID);

        assertThat(response.getProposedCount()).isEqualTo(1);
        assertThat(response.getAmbiguousCount()).isEqualTo(1);
        ArgumentCaptor<Header> header = ArgumentCaptor.forClass(Header.class);
        verify(writer, times(1)).create(eq(recon), header.capture(), eq(List.of(clear)), anyList(), anyString());
        assertThat(header.getValue().state()).isEqualTo(MatchState.PROPOSED);
        assertThat(header.getValue().origin()).isEqualTo(MatchOrigin.RULE);
        assertThat(header.getValue().kind()).isEqualTo(MatchKind.ONE_TO_ONE);
        assertThat(header.getValue().confidenceScore()).isEqualTo(100);
    }

    @Test
    @DisplayName("auto-match leaves a top candidate below 90 alone, and uses a line once per run")
    void autoMatchThresholdAndReuse() {
        BankTransaction a = transaction("50.00", LocalDate.of(2026, 9, 3));
        BankTransaction b = transaction("50.00", LocalDate.of(2026, 9, 3));
        BankTransaction weak = transaction("10.00", LocalDate.of(2026, 9, 4));
        when(calculator.compute(recon)).thenReturn(withUnexplainedBank(List.of(a, b, weak)));
        when(bankMatches.findByBankTransactionIdInAndActiveTrue(anyCollection()))
                .thenReturn(List.of());
        LedgerLine only = line("50.00", LocalDate.of(2026, 9, 3));
        Map<UUID, List<ScoredLine>> ranked = new LinkedHashMap<>();
        ranked.put(a.getBankTransactionId(), List.of(scored(only, 100)));
        ranked.put(b.getBankTransactionId(), List.of(scored(only, 100)));
        ranked.put(weak.getBankTransactionId(), List.of(scored(line("10.00", LocalDate.of(2026, 9, 4)), 89)));
        when(finder.rankedForEach(eq(recon), anyList())).thenReturn(ranked);

        AutoMatchResponse response = service.autoMatch(RECON_ID);

        assertThat(response.getProposedCount()).isEqualTo(1);
        assertThat(response.getAmbiguousCount()).isEqualTo(1);
    }

    private static ReconciliationSnapshot withUnexplainedBank(List<BankTransaction> rows) {
        ReconciliationSnapshot empty = snapshot(terms("0", "0"));
        return new ReconciliationSnapshot(
                empty.terms(),
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                rows,
                List.of(),
                List.of(),
                List.of());
    }

    private static LedgerLine line(String amount, LocalDate date) {
        return LedgerLine.of(postedLine(amount, date));
    }

    private static ScoredLine scored(LedgerLine line, int points) {
        return new ScoredLine(line, new Score(points, List.of(), 0));
    }
}
