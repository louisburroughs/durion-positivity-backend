package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.intake.BankRecErrorCode;
import com.positivity.accounting.internal.bankrec.intake.BankRecException;
import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.bankrec.service.FunctionalCurrency;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.DepositRecordRequest;
import com.positivity.accounting.internal.dto.DepositResponse;
import com.positivity.accounting.internal.dto.DepositReversalRequest;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.UndepositedSessionsResponse;
import com.positivity.accounting.internal.entity.Deposit;
import com.positivity.accounting.internal.entity.DepositSession;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.UndepositedSession;
import com.positivity.accounting.internal.entity.UndepositedSessionDrop;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.DepositStatus;
import com.positivity.accounting.internal.enums.UndepositedSessionStatus;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.DepositRepository;
import com.positivity.accounting.internal.repository.DepositSessionRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionDropRepository;
import com.positivity.accounting.internal.repository.UndepositedSessionRepository;
import com.positivity.accounting.internal.security.AccountingPermissions;
import com.positivity.domainevents.accounting.DepositRecordedV1;
import com.positivity.domainevents.location.LocationAncestry.AncestorSets;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationAncestorResolver;
import com.positivity.security.common.LocationScope;
import com.positivity.security.common.LocationScopeDeniedException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Record bank deposit and Reverse deposit (CAP:550 S18, #2514): the balance identity, whole sessions, idempotency,
 * the reversal through the journal-entry reversal and its reaction, the bank-account and currency rules, and the
 * location gate on stored sessions. The repositories are in-memory doubles; the posting is a mock whose requests are
 * asserted line by line.
 */
@DisplayName("Bank deposits of drawer cash (#2514)")
class DepositServiceImplTest {

    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-000000001000");
    private static final UUID UNDEPOSITED_FUNDS = UUID.fromString("019a0000-0000-7000-8000-000000001090");
    private static final UUID CASH_CLEARING = UUID.fromString("019a0000-0000-7000-8000-000000001095");
    private static final UUID SHOP_A = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final UUID SHOP_B = UUID.fromString("019a0000-0000-7000-8000-00000000a002");
    private static final UUID REGION = UUID.fromString("019a0000-0000-7000-8000-00000000a000");
    private static final LocalDate DEPOSIT_DATE = LocalDate.of(2026, 10, 8);
    private static final Instant CLOSED_AT = Instant.parse("2026-10-07T22:00:00Z");

    private final UndepositedSessionRepository sessions = mock(UndepositedSessionRepository.class);
    private final UndepositedSessionDropRepository drops = mock(UndepositedSessionDropRepository.class);
    private final DepositRepository deposits = mock(DepositRepository.class);
    private final DepositSessionRepository depositSessions = mock(DepositSessionRepository.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final JournalEntryRepository journalEntryRows = mock(JournalEntryRepository.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final BankAccountCurrencies currencies = mock(BankAccountCurrencies.class);
    private final DepositFacts facts = mock(DepositFacts.class);

    private final Map<UUID, UndepositedSession> sessionRows = new LinkedHashMap<>();
    private final Map<UUID, List<UndepositedSessionDrop>> dropRows = new HashMap<>();
    private final Map<UUID, Deposit> depositRows = new LinkedHashMap<>();
    private final List<DepositSession> depositSessionRows = new ArrayList<>();
    private final Map<UUID, JournalEntryResponse> entries = new HashMap<>();
    private final List<JournalEntryCreateRequest> created = new ArrayList<>();
    private GLAccount bank;
    private DepositServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        signIn(LocationScope.unscoped());
        service = new DepositServiceImpl(
                sessions,
                drops,
                deposits,
                depositSessions,
                glAccounts,
                resolver,
                journalEntries,
                auditLogs,
                zoneResolver,
                new LedgerCurrency("USD"),
                new FunctionalCurrency(new LedgerCurrency("USD")),
                currencies,
                facts,
                mock(ObjectProvider.class));
        DepositReversalReaction reaction = new DepositReversalReaction(
                deposits,
                depositSessions,
                sessions,
                journalEntryRows,
                auditLogs,
                facts,
                Clock.fixed(Instant.parse("2026-10-09T12:00:00Z"), ZoneOffset.UTC));

        bank = account(BANK, "1000", "Cash", AccountSubtype.BANK_CASH, true);
        when(glAccounts.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(Map.of(BANK, bank).get(invocation.<UUID>getArgument(0))));
        when(glAccounts.findAllById(any()))
                .thenReturn(List.of(
                        account(UNDEPOSITED_FUNDS, "1090", "Undeposited Funds", AccountSubtype.UNDEPOSITED_FUNDS, true),
                        account(CASH_CLEARING, "1095", "Register Cash Clearing", AccountSubtype.CURRENT_ASSET, false)));
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("USD"));
        when(zoneResolver.today()).thenReturn(LocalDate.of(2026, 10, 9));
        when(zoneResolver.postingDate(any()))
                .thenAnswer(invocation -> LocalDate.ofInstant(invocation.getArgument(0), ZoneOffset.UTC));
        when(resolver.resolveGLAccount(eq("BANK_DEPOSIT"), eq("UNDEPOSITED_FUNDS"), any(LocalDateTime.class)))
                .thenReturn(UNDEPOSITED_FUNDS);
        when(resolver.resolveGLAccount(eq("BANK_DEPOSIT"), eq("CASH_CLEARING"), any(LocalDateTime.class)))
                .thenReturn(CASH_CLEARING);

        // In-memory sessions and drops.
        when(sessions.findByStatusOrderByClosedAtAscSessionIdAsc(any()))
                .thenAnswer(invocation -> sessionRows.values().stream()
                        .filter(row -> row.getStatus() == invocation.getArgument(0))
                        .sorted(Comparator.comparing(UndepositedSession::getClosedAt))
                        .toList());
        when(sessions.lockBySessionIdIn(any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(0).stream()
                        .map(sessionRows::get)
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparing(UndepositedSession::getSessionId))
                        .toList());
        when(sessions.findBySessionIdIn(any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(0).stream()
                        .map(sessionRows::get)
                        .filter(Objects::nonNull)
                        .toList());
        when(sessions.lockByDepositId(any()))
                .thenAnswer(invocation -> sessionRows.values().stream()
                        .filter(row -> invocation.getArgument(0).equals(row.getDepositId()))
                        .toList());
        when(sessions.saveAllAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(drops.findByUndepositedSessionIdInOrderByOccurredAtAscMovementIdAsc(any()))
                .thenAnswer(invocation -> invocation.<Collection<UUID>>getArgument(0).stream()
                        .flatMap(id -> dropRows.getOrDefault(id, List.of()).stream())
                        .toList());

        // In-memory deposits.
        when(deposits.findByRequestId(any()))
                .thenAnswer(invocation -> depositRows.values().stream()
                        .filter(row -> invocation.getArgument(0).equals(row.getRequestId()))
                        .findFirst());
        when(deposits.findByReversalRequestId(any()))
                .thenAnswer(invocation -> depositRows.values().stream()
                        .filter(row -> invocation.getArgument(0).equals(row.getReversalRequestId()))
                        .findFirst());
        when(deposits.findById(any()))
                .thenAnswer(invocation -> Optional.ofNullable(depositRows.get(invocation.<UUID>getArgument(0))));
        when(deposits.lockByJournalEntryId(any()))
                .thenAnswer(invocation -> depositRows.values().stream()
                        .filter(row -> invocation.getArgument(0).equals(row.getJournalEntryId()))
                        .findFirst());
        when(deposits.saveAndFlush(any())).thenAnswer(invocation -> {
            Deposit row = invocation.getArgument(0);
            if (row.getDepositId() == null) {
                row.setDepositId(UUID.randomUUID());
            }
            depositRows.put(row.getDepositId(), row);
            return row;
        });
        when(depositSessions.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<DepositSession> rows = new ArrayList<>(invocation.<Collection<DepositSession>>getArgument(0));
            depositSessionRows.addAll(rows);
            return rows;
        });
        when(depositSessions.findByDepositIdOrderByClosedAtAscSessionIdAsc(any()))
                .thenAnswer(invocation -> depositSessionRows.stream()
                        .filter(row -> invocation.getArgument(0).equals(row.getDepositId()))
                        .toList());

        // The posting: created entries are numbered; the reversal hands its event to the reaction, as Spring does.
        when(journalEntries.createJournalEntry(any())).thenAnswer(invocation -> {
            JournalEntryCreateRequest request = invocation.getArgument(0);
            created.add(request);
            JournalEntryResponse entry = JournalEntryResponse.builder()
                    .journalEntryId(UUID.randomUUID())
                    .entryNumber("JE-202610-" + (entries.size() + 41))
                    .build();
            entries.put(entry.getJournalEntryId(), entry);
            return entry;
        });
        // any() matches null too: the override and the reversal date are optional.
        when(journalEntries.postJournalEntry(any(UUID.class), any()))
                .thenAnswer(invocation -> entries.get(invocation.<UUID>getArgument(0)));
        when(journalEntries.reverseJournalEntry(any(UUID.class), anyString(), any(), any()))
                .thenAnswer(invocation -> reverse(
                        reaction,
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2),
                        invocation.getArgument(3)));
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    // ---- the worked example ------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: with the session selected the read shows depositAmount 1,197.00, expectedCash 1,240.00,"
            + " clearingNet -43.00, difference 0 and the preview Dr bank / Dr 1095 43.00 / Cr 1090 1,240.00")
    void selectionOfTheWorkedExample() {
        UndepositedSession session = workedExample(SHOP_A);

        UndepositedSessionsResponse read = service.undeposited(List.of(session.getSessionId()), BANK);

        assertThat(read.asOf()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(read.currencyCode()).isEqualTo("USD");
        assertThat(read.sessions()).singleElement().satisfies(view -> {
            assertThat(view.terminalId()).isEqualTo("T-7");
            assertThat(view.closeDate()).isEqualTo(LocalDate.of(2026, 10, 7));
            assertThat(view.ageDays()).isEqualTo(2);
            assertThat(view.drops())
                    .extracting(UndepositedSessionsResponse.Drop::bagNumber)
                    .containsExactly("B-0912");
        });
        UndepositedSessionsResponse.Selection selection = read.selection();
        assertThat(selection.depositAmount()).isEqualByComparingTo("1197.00");
        assertThat(selection.expectedCash()).isEqualByComparingTo("1240.00");
        assertThat(selection.clearingNet()).isEqualByComparingTo("-43.00");
        assertThat(selection.difference()).isEqualByComparingTo("0");
        assertThat(selection.balanced()).isTrue();
        assertThat(selection.lines())
                .extracting(
                        UndepositedSessionsResponse.PreviewLine::accountNumber,
                        UndepositedSessionsResponse.PreviewLine::side,
                        l -> l.amount().toPlainString())
                .containsExactly(
                        tuple("1000", UndepositedSessionsResponse.Side.DEBIT, "1197.00"),
                        tuple("1090", UndepositedSessionsResponse.Side.CREDIT, "1240.00"),
                        tuple("1095", UndepositedSessionsResponse.Side.DEBIT, "43.00"));
        assertThat(created).as("nothing posts on the read").isEmpty();
    }

    @Test
    @DisplayName("AC2: recorded into 1000, one entry Dr 1000 1,197.00 / Cr 1090 1,240.00 / Dr 1095 43.00 dated"
            + " depositDate, the session DEPOSITED and the fact queued")
    void recordTheWorkedExample() {
        UndepositedSession session = workedExample(SHOP_A);

        DepositService.Outcome outcome = service.record(request(UUID.randomUUID(), session.getSessionId()));

        JournalEntryCreateRequest entry = onlyEntry();
        assertThat(entry.getTransactionDate()).isEqualTo(DEPOSIT_DATE.atStartOfDay());
        assertThat(entry.getSourceEventType()).isEqualTo("BANK_DEPOSIT");
        assertThat(entry.getSourceEventId())
                .as("derived from the command's requestId, the deposit's durable natural key")
                .isEqualTo(DepositServiceImpl.sourceEventId(
                        depositRows.values().iterator().next().getRequestId()));
        assertThat(entry.getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        l -> l.getDebitAmount().toPlainString(),
                        l -> l.getCreditAmount().toPlainString())
                .containsExactly(
                        tuple(BANK, "1197.00", "0"),
                        tuple(UNDEPOSITED_FUNDS, "0", "1240.00"),
                        tuple(CASH_CLEARING, "43.00", "0"));
        verify(journalEntries).postJournalEntry(any(UUID.class), isNull());

        DepositResponse response = outcome.response();
        assertThat(outcome.replayed()).isFalse();
        assertThat(response.status()).isEqualTo(DepositStatus.RECORDED);
        assertThat(response.amount()).isEqualByComparingTo("1197.00");
        assertThat(response.currencyCode()).isEqualTo("USD");
        assertThat(response.journalEntryNumber()).isEqualTo("JE-202610-41");
        assertThat(response.depositSlipReference()).isEqualTo("DS-20261008-01");
        assertThat(response.recordedBy()).isEqualTo("clerk.ann");
        assertThat(response.sessions()).singleElement().satisfies(s -> {
            assertThat(s.sessionId()).isEqualTo(session.getSessionId());
            assertThat(s.bagNumbers()).containsExactly("B-0912");
        });
        assertThat(session.getStatus()).isEqualTo(UndepositedSessionStatus.DEPOSITED);
        assertThat(session.getDepositId()).isEqualTo(response.depositId());
        verify(facts).changed(any(Deposit.class), any(), eq("clerk.ann"));
        verify(auditLogs).save(any());
    }

    @Test
    @DisplayName(
            "AC3 [M]: drops of 1,190.00 against 1,197.00 are 422 DEPOSIT_UNBALANCED naming 7.00; no entry, no plug")
    void unbalancedSelectionIsRefused() {
        UndepositedSession session = session(SHOP_A, "1240.00", "-43.00", drop("1190.00", "B-0913"));

        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), session.getSessionId())))
                .isInstanceOfSatisfying(CashSetupException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CashSetupException.Code.DEPOSIT_UNBALANCED);
                    assertThat(e.getMessage()).contains("7.00").contains("short");
                });
        verify(journalEntries, never()).createJournalEntry(any());
        assertThat(depositRows).isEmpty();
        assertThat(session.getStatus()).isEqualTo(UndepositedSessionStatus.UNDEPOSITED);
        assertThat(service.undeposited(List.of(session.getSessionId()), null).selection())
                .satisfies(selection -> {
                    assertThat(selection.difference()).isEqualByComparingTo("-7.00");
                    assertThat(selection.balanced()).isFalse();
                });
    }

    @Test
    @DisplayName("AC4: the same requestId twice is one deposit and one entry, the second replayed; another body is"
            + " 409 IDEMPOTENCY_CONFLICT")
    void recordIsIdempotent() {
        UndepositedSession session = workedExample(SHOP_A);
        UUID requestId = UUID.randomUUID();

        DepositService.Outcome first = service.record(request(requestId, session.getSessionId()));
        DepositService.Outcome second = service.record(request(requestId, session.getSessionId()));

        assertThat(second.replayed()).isTrue();
        assertThat(second.response().replayed()).isTrue();
        assertThat(second.response().depositId()).isEqualTo(first.response().depositId());
        assertThat(second.response().journalEntryNumber())
                .isEqualTo(first.response().journalEntryNumber());
        assertThat(created).hasSize(1);
        assertThat(depositRows).hasSize(1);

        DepositRecordRequest otherBody = new DepositRecordRequest(
                BANK, DEPOSIT_DATE.plusDays(1), "USD", List.of(session.getSessionId()), requestId, null, null);
        assertThatThrownBy(() -> service.record(otherBody))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("AC5: a reversal reverses the entry, the deposit is REVERSED and the session UNDEPOSITED again; a"
            + " replay is the first result and a second reversal is 409 DEPOSIT_ALREADY_REVERSED")
    void reverseRestoresTheSessions() {
        UndepositedSession session = workedExample(SHOP_A);
        DepositResponse recorded = service.record(request(UUID.randomUUID(), session.getSessionId()))
                .response();
        signIn(LocationScope.unscoped(), "controller.cfo");
        UUID requestId = UUID.randomUUID();
        DepositReversalRequest reversal =
                new DepositReversalRequest("Deposited into the wrong bank account", null, null, requestId);

        DepositService.Outcome outcome = service.reverse(recorded.depositId(), reversal);

        verify(journalEntries)
                .reverseJournalEntry(recorded.journalEntryId(), "Deposited into the wrong bank account", null, null);
        DepositResponse response = outcome.response();
        assertThat(response.status()).isEqualTo(DepositStatus.REVERSED);
        assertThat(response.reversalJournalEntryNumber()).isEqualTo("JE-202610-99");
        assertThat(response.reversalReason()).isEqualTo("Deposited into the wrong bank account");
        assertThat(response.reversedBy()).isEqualTo("controller.cfo");
        assertThat(session.getStatus()).isEqualTo(UndepositedSessionStatus.UNDEPOSITED);
        assertThat(session.getDepositId()).isNull();
        ArgumentCaptor<Deposit> queued = ArgumentCaptor.forClass(Deposit.class);
        verify(facts, times(2)).changed(queued.capture(), any(), any());
        assertThat(DepositFacts.factOf(queued.getValue(), depositSessionRows).status())
                .isEqualTo(DepositRecordedV1.Status.REVERSED);

        DepositService.Outcome replay = service.reverse(recorded.depositId(), reversal);
        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().status()).isEqualTo(DepositStatus.REVERSED);
        verify(journalEntries, times(1)).reverseJournalEntry(any(UUID.class), anyString(), any(), any());

        DepositReversalRequest again =
                new DepositReversalRequest("Deposited into the wrong bank account", null, null, UUID.randomUUID());
        assertThatThrownBy(() -> service.reverse(recorded.depositId(), again))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_ALREADY_REVERSED);

        // The record replay still answers with the deposit as it was recorded (the first result).
        DepositService.Outcome recordReplay =
                service.record(request(depositRows.get(recorded.depositId()).getRequestId(), session.getSessionId()));
        assertThat(recordReplay.response().status()).isEqualTo(DepositStatus.RECORDED);
        assertThat(recordReplay.response().reversalJournalEntryId()).isNull();
        // GET answers with the deposit as it stands.
        assertThat(service.get(recorded.depositId()).status()).isEqualTo(DepositStatus.REVERSED);
    }

    @Test
    @DisplayName("AC6: a closed period propagates PERIOD_CLOSED and records nothing; an override justification goes"
            + " to the period gate")
    void depositDatePassesThePeriodGate() {
        UndepositedSession session = workedExample(SHOP_A);
        when(journalEntries.postJournalEntry(any(UUID.class), isNull()))
                .thenThrow(new AccountingPeriodClosedException("2026-10", "Period 2026-10 is closed"));

        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), session.getSessionId())))
                .isInstanceOf(AccountingPeriodClosedException.class);
        assertThat(depositRows).isEmpty();

        DepositRecordRequest overridden = new DepositRecordRequest(
                BANK,
                DEPOSIT_DATE,
                "USD",
                List.of(session.getSessionId()),
                UUID.randomUUID(),
                null,
                "Slip found after the month was closed");
        service.record(overridden);
        verify(journalEntries).postJournalEntry(any(UUID.class), eq("Slip found after the month was closed"));
        assertThat(depositRows.values())
                .singleElement()
                .satisfies(deposit -> assertThat(deposit.getOverrideJustification())
                        .isEqualTo("Slip found after the month was closed"));
    }

    @Test
    @DisplayName("AC7: a card-only session with an over of 2.00 dropped to the bag adds no expected cash: no card"
            + " amount is in the deposit")
    void cardOnlySessionAddsNoExpectedCash() {
        UndepositedSession cash = workedExample(SHOP_A);
        UndepositedSession cardOnly = session(SHOP_A, "0.00", "2.00", drop("2.00", "B-0914"));

        DepositResponse response = service.record(
                        request(UUID.randomUUID(), cash.getSessionId(), cardOnly.getSessionId()))
                .response();

        assertThat(response.amount()).isEqualByComparingTo("1199.00");
        assertThat(response.expectedCash()).as("the cash session's sales only").isEqualByComparingTo("1240.00");
        assertThat(response.clearingNet()).isEqualByComparingTo("-41.00");
        assertThat(onlyEntry().getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        l -> l.getDebitAmount().toPlainString(),
                        l -> l.getCreditAmount().toPlainString())
                .containsExactly(
                        tuple(BANK, "1199.00", "0"),
                        tuple(UNDEPOSITED_FUNDS, "0", "1240.00"),
                        tuple(CASH_CLEARING, "41.00", "0"));
        assertThat(cardOnly.getStatus()).isEqualTo(UndepositedSessionStatus.DEPOSITED);
    }

    @Test
    @DisplayName("review MINOR-1: no drops against expected cash 1,240.00 is 422 DEPOSIT_UNBALANCED naming 1,240.00 and"
            + " counted; a selection balanced at zero drops is 400, no cash to take to the bank")
    @SuppressWarnings("unchecked")
    void missingDropsAreUnbalanced() {
        io.micrometer.core.instrument.MeterRegistry registry =
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
        ObjectProvider<io.micrometer.core.instrument.MeterRegistry> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(registry);
        DepositServiceImpl counted = new DepositServiceImpl(
                sessions,
                drops,
                deposits,
                depositSessions,
                glAccounts,
                resolver,
                journalEntries,
                auditLogs,
                zoneResolver,
                new LedgerCurrency("USD"),
                new FunctionalCurrency(new LedgerCurrency("USD")),
                currencies,
                facts,
                provider);
        UndepositedSession noDrop = session(SHOP_A, "1240.00", "0.00");

        assertThatThrownBy(() -> counted.record(request(UUID.randomUUID(), noDrop.getSessionId())))
                .isInstanceOfSatisfying(CashSetupException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CashSetupException.Code.DEPOSIT_UNBALANCED);
                    assertThat(e.getMessage()).contains("1240.00").contains("short");
                });
        assertThat(registry.get(DepositServiceImpl.UNBALANCED_METRIC).counter().count())
                .isEqualTo(1.0);

        // Petty 20.00 paid from 20.00 of cash sales, nothing dropped: balanced, but no bank line to post.
        UndepositedSession balancedAtZero = session(SHOP_A, "20.00", "-20.00");
        assertThatThrownBy(() -> counted.record(request(UUID.randomUUID(), balancedAtZero.getSessionId())))
                .isInstanceOf(InvalidRequestParameterException.class)
                .hasMessageContaining("no bank drops");
        assertThat(registry.get(DepositServiceImpl.UNBALANCED_METRIC).counter().count())
                .isEqualTo(1.0);
        assertThat(created).isEmpty();
    }

    @Test
    @DisplayName("review MINOR-2: a NOTHING_TO_DEPOSIT session is never listed and a deposit naming it is 400")
    void nothingToDepositIsNeverListedOrTaken() {
        UndepositedSession cash = workedExample(SHOP_A);
        UndepositedSession nothing = session(SHOP_A, "0.00", "0.00");
        nothing.setStatus(UndepositedSessionStatus.NOTHING_TO_DEPOSIT);

        assertThat(service.undeposited(List.of(), null).sessions())
                .extracting(UndepositedSessionsResponse.Session::sessionId)
                .containsExactly(cash.getSessionId());
        assertThatThrownBy(
                        () -> service.record(request(UUID.randomUUID(), cash.getSessionId(), nothing.getSessionId())))
                .isInstanceOf(InvalidRequestParameterException.class)
                .hasMessageContaining("nothing to deposit");
        assertThat(created).isEmpty();
        assertThat(nothing.getStatus()).isEqualTo(UndepositedSessionStatus.NOTHING_TO_DEPOSIT);
    }

    @Test
    @DisplayName("review MAJOR-1: the reversal entry of a deposit is never reversed (409"
            + " DEPOSIT_REVERSAL_NOT_REVERSIBLE)")
    void reversalEntryIsNeverReversed() {
        UndepositedSession session = workedExample(SHOP_A);
        DepositResponse recorded = service.record(request(UUID.randomUUID(), session.getSessionId()))
                .response();
        DepositResponse reversed = service.reverse(
                        recorded.depositId(),
                        new DepositReversalRequest(
                                "Deposited into the wrong bank account", null, null, UUID.randomUUID()))
                .response();
        assertThat(depositRows.get(recorded.depositId()).getReversalRequestId())
                .as("the reaction stamps the command's request on the deposit it reverses")
                .isNotNull();

        DepositReversalReaction reaction = new DepositReversalReaction(
                deposits,
                depositSessions,
                sessions,
                journalEntryRows,
                auditLogs,
                facts,
                Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC));
        when(deposits.lockByReversalJournalEntryId(reversed.reversalJournalEntryId()))
                .thenReturn(Optional.of(depositRows.get(recorded.depositId())));
        LedgerReversalApplied reversalOfTheReversal = new LedgerReversalApplied(
                reversed.reversalJournalEntryId(),
                UUID.randomUUID(),
                DEPOSIT_DATE,
                List.of(UUID.randomUUID()),
                Set.of(BANK),
                "controller.cfo",
                null,
                "Undo the wrong reversal");
        assertThatThrownBy(() -> reaction.onReversed(reversalOfTheReversal))
                .isInstanceOfSatisfying(CashSetupException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CashSetupException.Code.DEPOSIT_REVERSAL_NOT_REVERSIBLE);
                    assertThat(e.getMessage()).contains("record the deposit again");
                });
        assertThat(depositRows.get(recorded.depositId()).getStatus()).isEqualTo(DepositStatus.REVERSED);
        assertThat(session.getStatus()).isEqualTo(UndepositedSessionStatus.UNDEPOSITED);
    }

    @Test
    @DisplayName("review LOW-1: Reverse deposit takes no lock before the reversal; losing the entry's race to another"
            + " reversal is 409 DEPOSIT_ALREADY_REVERSED")
    void lostReversalRaceIsAlreadyReversed() {
        UndepositedSession session = workedExample(SHOP_A);
        DepositResponse recorded = service.record(request(UUID.randomUUID(), session.getSessionId()))
                .response();
        // doThrow: re-stubbing with when() would run the stubbed reversal.
        org.mockito.Mockito.doThrow(new JournalEntryNotReversibleException(
                        recorded.journalEntryId(),
                        com.positivity.accounting.internal.enums.JournalEntryStatus.REVERSED))
                .when(journalEntries)
                .reverseJournalEntry(any(UUID.class), anyString(), any(), any());

        assertThatThrownBy(() -> service.reverse(
                        recorded.depositId(),
                        new DepositReversalRequest(
                                "Deposited into the wrong bank account", null, null, UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_ALREADY_REVERSED);
        verify(deposits, never()).lockByJournalEntryId(any());
        assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.getResourceMap())
                .as("the request is unbound again")
                .isEmpty();
    }

    @Test
    @DisplayName("review LOW-3: the read's preview takes only a bank account Record would take (422"
            + " DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE)")
    void previewBankAccountIsEligible() {
        UndepositedSession session = workedExample(SHOP_A);
        bank.setReconcilable(false);

        assertThatThrownBy(() -> service.undeposited(List.of(session.getSessionId()), BANK))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE);
        assertThatThrownBy(() -> service.undeposited(List.of(session.getSessionId()), UUID.randomUUID()))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE);
        assertThat(service.undeposited(List.of(session.getSessionId()), null)
                        .selection()
                        .lines()
                        .get(0)
                        .accountNumber())
                .as("without a bank account the bank line names none")
                .isNull();
    }

    // ---- whole sessions, accounts, currency ---------------------------------------------------------------------

    @Test
    @DisplayName("a session already in a standing deposit is 409 DEPOSIT_SESSION_ALREADY_DEPOSITED naming that"
            + " deposit; an unknown session is 400")
    void sessionsAreDepositedOnce() {
        UndepositedSession session = workedExample(SHOP_A);
        service.record(request(UUID.randomUUID(), session.getSessionId()));

        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), session.getSessionId())))
                .isInstanceOfSatisfying(CashSetupException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(CashSetupException.Code.DEPOSIT_SESSION_ALREADY_DEPOSITED);
                    assertThat(e.getMessage()).contains("JE-202610-41");
                    assertThat(e.getReferenceId())
                            .isEqualTo(session.getSessionId().toString());
                });
        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.undeposited(List.of(session.getSessionId()), null))
                .as("a deposited session is not selectable")
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThat(created).hasSize(1);
    }

    @Test
    @DisplayName("the bank account must be an active, reconcilable BANK_CASH account in functional currency (422"
            + " DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE); another currency is 422 CURRENCY_NOT_SUPPORTED")
    void bankAccountAndCurrency() {
        UndepositedSession session = workedExample(SHOP_A);
        UUID sessionId = session.getSessionId();

        bank.setReconcilable(false);
        assertNotEligible(sessionId);
        bank.setReconcilable(true);
        bank.setAccountSubtype(AccountSubtype.CURRENT_ASSET);
        assertNotEligible(sessionId);
        bank.setAccountSubtype(AccountSubtype.BANK_CASH);
        bank.setDeactivationDate(DEPOSIT_DATE.atStartOfDay());
        assertNotEligible(sessionId);
        bank.setDeactivationDate(null);
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("CAD"));
        assertNotEligible(sessionId);
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("USD"));

        DepositRecordRequest cad =
                new DepositRecordRequest(BANK, DEPOSIT_DATE, "CAD", List.of(sessionId), UUID.randomUUID(), null, null);
        assertThatThrownBy(() -> service.record(cad)).isInstanceOf(CurrencyNotSupportedException.class);
        assertThat(created).isEmpty();
    }

    @Test
    @DisplayName("ADR-0067 PC-6: a selection amount finer than the minor unit is 422 AMOUNT_PRECISION_EXCEEDS_CURRENCY")
    void precisionIsTheCurrencys() {
        UndepositedSession session = session(SHOP_A, "100.005", "0.00", drop("100.005", "B-1"));

        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), session.getSessionId())))
                .isInstanceOfSatisfying(BankRecException.class, e -> {
                    assertThat(e.code()).isEqualTo(BankRecErrorCode.AMOUNT_PRECISION_EXCEEDS_CURRENCY);
                    assertThat(e.fieldErrors()).containsKeys("selection.depositAmount", "selection.expectedCash");
                });
        assertThat(created).isEmpty();
    }

    // ---- location scope (ADR-0061) -------------------------------------------------------------------------------

    @Test
    @DisplayName(
            "ADR-0061: a caller scoped to a region lists only its sessions, cannot select, deposit, read or reverse"
                    + " another location's, and is denied without the location being named")
    void locationScope() {
        UndepositedSession inReach = workedExample(SHOP_A);
        UndepositedSession outOfReach = workedExample(SHOP_B);
        UndepositedSession noLocation = workedExample(null);
        signIn(scopedTo(AccountingPermissions.DEPOSIT_CREATE, AccountingPermissions.DEPOSIT_REVERSE));

        assertThat(service.undeposited(List.of(), null).sessions())
                .extracting(UndepositedSessionsResponse.Session::sessionId)
                .containsExactly(inReach.getSessionId());
        assertThatThrownBy(() -> service.undeposited(List.of(outOfReach.getSessionId()), null))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), outOfReach.getSessionId())))
                .isInstanceOfSatisfying(
                        LocationScopeDeniedException.class,
                        e -> assertThat(e.getMessage())
                                .as("the denial never names the location")
                                .doesNotContain(SHOP_B.toString()));
        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), noLocation.getSessionId())))
                .isInstanceOf(LocationScopeDeniedException.class);
        assertThat(created).isEmpty();
        assertThat(outOfReach.getStatus()).isEqualTo(UndepositedSessionStatus.UNDEPOSITED);

        // A deposit of another location's drawer, recorded by an unscoped caller, is neither readable nor reversible.
        signIn(LocationScope.unscoped());
        DepositResponse other = service.record(request(UUID.randomUUID(), outOfReach.getSessionId()))
                .response();
        signIn(scopedTo(AccountingPermissions.DEPOSIT_CREATE, AccountingPermissions.DEPOSIT_REVERSE));
        assertThatThrownBy(() -> service.get(other.depositId())).isInstanceOf(LocationScopeDeniedException.class);
        assertThatThrownBy(() -> service.reverse(
                        other.depositId(),
                        new DepositReversalRequest(
                                "Deposited into the wrong bank account", null, null, UUID.randomUUID())))
                .isInstanceOf(LocationScopeDeniedException.class);
        verify(journalEntries, never()).reverseJournalEntry(any(UUID.class), anyString(), any(), any());

        // In reach, it records.
        assertThat(service.record(request(UUID.randomUUID(), inReach.getSessionId()))
                        .response()
                        .status())
                .isEqualTo(DepositStatus.RECORDED);
    }

    @Test
    @DisplayName("an unknown deposit is 404 DEPOSIT_NOT_FOUND on read and reversal")
    void unknownDeposit() {
        UUID missing = UUID.randomUUID();
        assertThatThrownBy(() -> service.get(missing))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_NOT_FOUND);
        assertThatThrownBy(() -> service.reverse(
                        missing,
                        new DepositReversalRequest(
                                "Deposited into the wrong bank account", null, null, UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_NOT_FOUND);
    }

    // ---- fixtures ------------------------------------------------------------------------------------------------

    private void assertNotEligible(UUID sessionId) {
        assertThatThrownBy(() -> service.record(request(UUID.randomUUID(), sessionId)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.DEPOSIT_BANK_ACCOUNT_NOT_ELIGIBLE);
    }

    private Object reverse(
            DepositReversalReaction reaction, UUID original, String reason, LocalDate date, String override) {
        UUID reversalId = UUID.randomUUID();
        JournalEntry reversalEntry = new JournalEntry();
        reversalEntry.setJournalEntryId(reversalId);
        reversalEntry.setEntryNumber("JE-202610-99");
        when(journalEntryRows.findById(reversalId)).thenReturn(Optional.of(reversalEntry));
        reaction.onReversed(new LedgerReversalApplied(
                original,
                reversalId,
                date == null ? DEPOSIT_DATE : date,
                List.of(UUID.randomUUID()),
                Set.of(BANK),
                currentUser(),
                override,
                reason));
        return JournalEntryResponse.builder()
                .journalEntryId(reversalId)
                .entryNumber("JE-202610-99")
                .build();
    }

    private UndepositedSession workedExample(UUID location) {
        return session(location, "1240.00", "-43.00", drop("1197.00", "B-0912"));
    }

    private UndepositedSession session(
            UUID location, String expectedCash, String clearingNet, UndepositedSessionDrop... sessionDrops) {
        UndepositedSession row = new UndepositedSession();
        row.setUndepositedSessionId(UUID.randomUUID());
        row.setSessionId(UUID.randomUUID());
        row.setTerminalId("T-7");
        row.setLocationId(location);
        row.setOpenedAt(CLOSED_AT.minusSeconds(28_800));
        row.setClosedAt(CLOSED_AT.plusSeconds(sessionRows.size()));
        row.setOpeningFloat(new BigDecimal("200.00"));
        row.setCountedCash(new BigDecimal("200.00"));
        row.setTheoreticalCash(new BigDecimal("203.00"));
        row.setOverShort(new BigDecimal("-3.00"));
        row.setExpectedCash(new BigDecimal(expectedCash));
        row.setClearingNet(new BigDecimal(clearingNet));
        BigDecimal deposit = BigDecimal.ZERO;
        List<UndepositedSessionDrop> rows = new ArrayList<>();
        for (UndepositedSessionDrop drop : sessionDrops) {
            drop.setUndepositedSessionId(row.getUndepositedSessionId());
            deposit = deposit.add(drop.getAmount());
            rows.add(drop);
        }
        row.setDepositAmount(deposit);
        row.setCurrencyCode("USD");
        row.setStatus(UndepositedSessionStatus.UNDEPOSITED);
        sessionRows.put(row.getSessionId(), row);
        dropRows.put(row.getUndepositedSessionId(), rows);
        return row;
    }

    private static UndepositedSessionDrop drop(String amount, String bag) {
        UndepositedSessionDrop drop = new UndepositedSessionDrop();
        drop.setDropId(UUID.randomUUID());
        drop.setMovementId(UUID.randomUUID());
        drop.setBagNumber(bag);
        drop.setAmount(new BigDecimal(amount));
        drop.setOccurredAt(CLOSED_AT.minusSeconds(600));
        return drop;
    }

    private static DepositRecordRequest request(UUID requestId, UUID... sessionIds) {
        return new DepositRecordRequest(
                BANK, DEPOSIT_DATE, "USD", List.of(sessionIds), requestId, "DS-20261008-01", null);
    }

    private JournalEntryCreateRequest onlyEntry() {
        assertThat(created).hasSize(1);
        return created.get(0);
    }

    private static GLAccount account(UUID id, String code, String name, AccountSubtype subtype, boolean reconcilable) {
        GLAccount account = new GLAccount(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountSubtype(subtype);
        account.setReconcilable(reconcilable);
        account.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        return account;
    }

    private static LocationScope scopedTo(String... permissions) {
        LocationAncestorResolver replica = locationId -> SHOP_A.equals(locationId)
                ? new AncestorSets(Set.of(SHOP_A, REGION), Set.of(SHOP_A, REGION))
                : new AncestorSets(Set.of(locationId), Set.of(locationId));
        return LocationScope.of(Set.of(permissions), Set.of(), Optional.of(Set.of(REGION)), true, replica);
    }

    private static String currentUser() {
        return SecurityContextHolder.getContext().getAuthentication().getName();
    }

    private static void signIn(LocationScope scope) {
        signIn(scope, "clerk.ann");
    }

    private static void signIn(LocationScope scope, String username) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                List.of(
                        new SimpleGrantedAuthority(AccountingPermissions.DEPOSIT_CREATE),
                        new SimpleGrantedAuthority(AccountingPermissions.DEPOSIT_REVERSE)));
        caller.setDetails(Map.of(
                GatewaySecurityConstants.DETAIL_USERNAME,
                username,
                GatewaySecurityConstants.DETAIL_LOCATION_SCOPE,
                scope));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }
}
