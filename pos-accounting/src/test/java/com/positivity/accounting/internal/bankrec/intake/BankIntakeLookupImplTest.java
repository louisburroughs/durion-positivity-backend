package com.positivity.accounting.internal.bankrec.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankAccountProfile;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.enums.BankStatementStatus;
import com.positivity.accounting.internal.bankrec.repository.BankAccountProfileRepository;
import com.positivity.accounting.internal.bankrec.repository.BankStatementRepository;
import com.positivity.accounting.internal.bankrec.repository.BankTransactionRepository;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts;
import com.positivity.accounting.internal.bankrec.service.BankCashAccounts.BankCashAccount;
import com.positivity.accounting.internal.bankrec.service.BankRecAuditRecorder;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.StatementHeader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The read side of the intake port (SPEC §4.2–§4.5; story S3, #2302). */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankIntakeLookupImpl (#2302)")
class BankIntakeLookupImplTest {

    private static final UUID ACCOUNT = UUID.fromString("5eed0acc-0000-4000-8000-000000001000");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-12-31T12:00:00Z"), ZoneOffset.UTC);
    private static final String ACK = "switched banks in August";

    @Mock
    private BankCashAccounts bankCashAccounts;

    @Mock
    private BankStatementRepository statements;

    @Mock
    private BankTransactionRepository transactions;

    @Mock
    private BankAccountProfileRepository profiles;

    @Mock
    private BankRecAuditRecorder audit;

    private BankIntakeLookupImpl lookup;

    @BeforeEach
    void setUp() {
        lookup = new BankIntakeLookupImpl(
                bankCashAccounts,
                new FunctionalCurrency(new LedgerCurrency("USD")),
                statements,
                transactions,
                profiles,
                audit,
                CLOCK);
    }

    private static StatementHeader header(String start, String end, String opening) {
        return new StatementHeader(
                null, LocalDate.parse(start), LocalDate.parse(end), new BigDecimal(opening), BigDecimal.TEN);
    }

    private static BankRecErrorCode codeOf(Throwable thrown) {
        return ((BankRecException) thrown).code();
    }

    @Test
    void anAccountWithoutAProfileTakesTheLedgerCurrencyAndNoMapping() {
        when(bankCashAccounts.requireForIntake(ACCOUNT)).thenReturn(new BankCashAccount(ACCOUNT, "1000", "Cash"));
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());

        BankIntakeLookup.BankAccountTerms terms = lookup.requireAccount(ACCOUNT);

        assertThat(terms.currency()).isEqualTo("USD");
        assertThat(terms.profileExists()).isFalse();
        assertThat(terms.defaultColumnMapping()).isNull();
        assertThat(terms.tolerance()).isEqualByComparingTo("0.01");
        assertThat(terms.display(new BigDecimal("9985"))).isEqualTo("9985.00");
    }

    @Test
    void anAccountWithAProfileTakesItsCurrencyAndSavedMapping() {
        when(bankCashAccounts.requireForIntake(ACCOUNT)).thenReturn(new BankCashAccount(ACCOUNT, "1000", "Cash"));
        BankAccountProfile profile = new BankAccountProfile(ACCOUNT);
        profile.setCurrency("JPY");
        profile.setDefaultColumnMapping(Map.of("date", "Posted"));
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.of(profile));

        BankIntakeLookup.BankAccountTerms terms = lookup.requireAccount(ACCOUNT);

        assertThat(terms.currency()).isEqualTo("JPY");
        assertThat(terms.tolerance()).isEqualByComparingTo("1");
        assertThat(terms.defaultColumnMapping()).containsEntry("date", "Posted");
    }

    @Test
    void theFirstStatementNeedsAnAcknowledgementAndAShortOneIsRefusedFirst() {
        StatementHeader first = header("2026-09-01", "2026-09-30", "100");

        assertThatThrownBy(() -> lookup.checkHeader(ACCOUNT, first, null))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.STATEMENT_NOT_CONTIGUOUS));
        assertThatThrownBy(() -> lookup.checkHeader(ACCOUNT, first, "short"))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.JUSTIFICATION_REQUIRED));

        BankIntakeLookup.HeaderCheck check = lookup.checkHeader(ACCOUNT, first, "  " + ACK + " ");
        assertThat(check.contiguous()).isFalse();
        assertThat(check.previousStatementId()).isNull();
        assertThat(check.gapAcknowledgement()).isEqualTo(ACK);
    }

    @Test
    void aContiguousStatementRefusesAnAcknowledgementAndOverlapIsCheckedBeforeContiguity() {
        BankStatement previous = new BankStatement();
        previous.setStatementId(UUID.fromString("01980000-0000-7000-8000-000000000001"));
        previous.setEndDate(LocalDate.parse("2026-08-31"));
        previous.setClosingBalance(new BigDecimal("100.00"));
        when(statements.findFirstByGlAccountIdAndStatusAndEndDateLessThanOrderByEndDateDesc(
                        eq(ACCOUNT), eq(BankStatementStatus.COMMITTED), any()))
                .thenReturn(Optional.of(previous));
        StatementHeader next = header("2026-09-01", "2026-09-30", "100");

        assertThat(lookup.checkHeader(ACCOUNT, next, null).contiguous()).isTrue();
        assertThatThrownBy(() -> lookup.checkHeader(ACCOUNT, next, ACK))
                .satisfies(e ->
                        assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.STATEMENT_GAP_ACKNOWLEDGEMENT_NOT_APPLICABLE));

        when(statements
                        .findFirstByGlAccountIdAndStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqualOrderByStartDateAsc(
                                eq(ACCOUNT), eq(BankStatementStatus.COMMITTED), any(), any()))
                .thenReturn(Optional.of(previous));
        assertThatThrownBy(() -> lookup.checkHeader(ACCOUNT, next, ACK))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo(BankRecErrorCode.STATEMENT_PERIOD_OVERLAP));
    }

    @Test
    void collidingFingerprintsNameTheEarliestTransactionPerFingerprint() {
        BankTransaction later = transaction("01980000-0000-7000-8000-000000000002", "fp", "2026-12-02T00:00:00Z");
        BankTransaction earlier = transaction("01980000-0000-7000-8000-000000000003", "fp", "2026-12-01T00:00:00Z");
        when(transactions.findByGlAccountIdAndFingerprintInAndStatusNotIn(
                        eq(ACCOUNT), anyCollection(), anyCollection()))
                .thenReturn(List.of(later, earlier));

        assertThat(lookup.collidingFingerprints(ACCOUNT, List.of("fp", "other")))
                .containsExactly(Map.entry("fp", earlier.getBankTransactionId()));
        assertThat(lookup.collidingFingerprints(ACCOUNT, List.of())).isEmpty();
    }

    @Test
    void collidingFingerprintsAreQueriedInBoundedChunksAndMerged() {
        // A 10 MiB file can carry far more fingerprints than Postgres accepts as bind parameters.
        List<String> fingerprints = java.util.stream.IntStream.range(0, 2 * BankIntakeLookupImpl.FINGERPRINT_CHUNK + 5)
                .mapToObj(i -> "fp-" + i)
                .toList();
        BankTransaction first = transaction("01980000-0000-7000-8000-000000000011", "fp-3", "2026-12-01T00:00:00Z");
        BankTransaction last = transaction(
                "01980000-0000-7000-8000-000000000012", "fp-" + (fingerprints.size() - 1), "2026-12-02T00:00:00Z");
        List<java.util.Collection<String>> chunks = new java.util.ArrayList<>();
        when(transactions.findByGlAccountIdAndFingerprintInAndStatusNotIn(
                        eq(ACCOUNT), anyCollection(), anyCollection()))
                .thenAnswer(inv -> {
                    java.util.Collection<String> chunk = List.copyOf(inv.<java.util.Collection<String>>getArgument(1));
                    chunks.add(chunk);
                    return java.util.stream.Stream.of(first, last)
                            .filter(t -> chunk.contains(t.getFingerprint()))
                            .toList();
                });

        Map<String, UUID> colliding = lookup.collidingFingerprints(ACCOUNT, fingerprints);

        assertThat(chunks)
                .hasSize(3)
                .allSatisfy(c -> assertThat(c).hasSizeLessThanOrEqualTo(BankIntakeLookupImpl.FINGERPRINT_CHUNK));
        assertThat(chunks.stream().mapToInt(java.util.Collection::size).sum()).isEqualTo(fingerprints.size());
        assertThat(colliding)
                .containsOnly(
                        Map.entry("fp-3", first.getBankTransactionId()),
                        Map.entry(last.getFingerprint(), last.getBankTransactionId()));
    }

    @Test
    void aDefaultMappingIsSavedAndAuditedOnlyOnAnExistingProfile() {
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.empty());
        assertThat(lookup.saveDefaultColumnMapping(ACCOUNT, Map.of("date", 0), "preparer"))
                .isFalse();
        verifyNoInteractions(audit);

        BankAccountProfile profile = new BankAccountProfile(ACCOUNT);
        profile.setCurrency("USD");
        when(profiles.findById(ACCOUNT)).thenReturn(Optional.of(profile));
        assertThat(lookup.saveDefaultColumnMapping(ACCOUNT, Map.of("date", 0), "preparer"))
                .isTrue();
        assertThat(profile.getDefaultColumnMapping()).containsEntry("date", 0);
        verify(audit)
                .record(
                        eq(BankRecAuditRecorder.BANK_ACCOUNT_PROFILE),
                        eq(ACCOUNT),
                        eq(BankRecAuditRecorder.BANK_ACCOUNT_PROFILE_SET),
                        eq("preparer"),
                        any(),
                        any(),
                        any());
        verify(statements, never()).saveAndFlush(any());
    }

    private static BankTransaction transaction(String id, String fingerprint, String firstObserved) {
        BankTransaction t = new BankTransaction();
        t.setBankTransactionId(UUID.fromString(id));
        t.setFingerprint(fingerprint);
        t.setFirstObservedAt(Instant.parse(firstObserved));
        return t;
    }
}
