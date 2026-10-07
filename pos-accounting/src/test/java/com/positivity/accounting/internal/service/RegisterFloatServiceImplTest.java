package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.readmodel.BankAccountCurrencies;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.RegisterFloatChangeRequest;
import com.positivity.accounting.internal.dto.RegisterFloatGoLiveRequest;
import com.positivity.accounting.internal.dto.RegisterFloatRelocationRequest;
import com.positivity.accounting.internal.entity.AccountingAuditLog;
import com.positivity.accounting.internal.entity.ExtOrderRegisterSession;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.enums.RegisterFloatRelocationReason;
import com.positivity.accounting.internal.enums.RegisterSessionStatus;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.exception.InvalidRequestParameterException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Register float commands (#2511)")
class RegisterFloatServiceImplTest {

    private static final UUID LOCATION = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final UUID FLOAT_ACCOUNT = UUID.fromString("019a0000-0000-7000-8000-000000001080");
    private static final UUID EQUITY_ACCOUNT = UUID.fromString("019a0000-0000-7000-8000-000000003900");
    private static final UUID BANK = UUID.fromString("019a0000-0000-7000-8000-000000001000");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 1);
    private static final LocalDate MOVE_DAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 20);
    private static final UUID SHOP_B = UUID.fromString("019a0000-0000-7000-8000-00000000a002");

    private final RegisterFloatRepository floats = mock(RegisterFloatRepository.class);
    private final RegisterFloatChangeRepository changes = mock(RegisterFloatChangeRepository.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final BankAccountCurrencies currencies = mock(BankAccountCurrencies.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final RegisterSessionReplica sessions = mock(RegisterSessionReplica.class);
    private final List<RegisterFloatChange> standing = new ArrayList<>();
    private RegisterFloat registerFloat;
    private RegisterFloatServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OutboxEventWriter> writers = mock(ObjectProvider.class);
        when(writers.getIfAvailable()).thenReturn(writer);
        ObjectProvider<MeterRegistry> meters = mock(ObjectProvider.class);
        RegisterFloatFacts facts = new RegisterFloatFacts(
                writers, Clock.fixed(Instant.parse("2026-10-01T12:00:00Z"), ZoneOffset.UTC), meters);
        service = new RegisterFloatServiceImpl(
                floats,
                changes,
                glAccounts,
                resolver,
                journalEntries,
                auditLogs,
                zoneResolver,
                new LedgerCurrency("USD"),
                currencies,
                facts,
                sessions);

        when(floats.lockByRegisterId("T-1")).thenAnswer(invocation -> Optional.ofNullable(registerFloat));
        when(floats.saveAndFlush(any())).thenAnswer(invocation -> {
            RegisterFloat saved = invocation.getArgument(0);
            if (saved.getRegisterFloatId() == null) {
                saved.setRegisterFloatId(UUID.randomUUID());
            }
            registerFloat = saved;
            return saved;
        });
        when(changes.findByRequestId(any())).thenReturn(Optional.empty());
        when(changes.findByRegisterFloatIdAndKindInAndReversalJournalEntryIdIsNull(any(), anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<RegisterFloatChangeKind> kinds = invocation.getArgument(1);
                    return standing.stream()
                            .filter(change ->
                                    kinds.contains(change.getKind()) && change.getReversalJournalEntryId() == null)
                            .toList();
                });
        when(changes.findByRegisterFloatIdAndKindIn(any(), anyCollection())).thenAnswer(invocation -> {
            Collection<RegisterFloatChangeKind> kinds = invocation.getArgument(1);
            return standing.stream()
                    .filter(change -> kinds.contains(change.getKind()))
                    .toList();
        });
        when(changes.saveAndFlush(any())).thenAnswer(invocation -> {
            RegisterFloatChange change = invocation.getArgument(0);
            standing.add(change);
            return change;
        });
        when(resolver.resolveGLAccount(eq("REGISTER_FLOAT"), eq("REGISTER_FLOAT"), any(LocalDateTime.class)))
                .thenReturn(FLOAT_ACCOUNT);
        when(resolver.resolveGLAccount(eq("REGISTER_FLOAT"), eq("OPENING_BALANCE_EQUITY"), any(LocalDateTime.class)))
                .thenReturn(EQUITY_ACCOUNT);
        when(journalEntries.createJournalEntry(any()))
                .thenAnswer(invocation -> JournalEntryResponse.builder()
                        .journalEntryId(UUID.randomUUID())
                        .build());
        when(journalEntries.postJournalEntry(any(UUID.class), any()))
                .thenAnswer(invocation -> JournalEntryResponse.builder()
                        .journalEntryId(invocation.getArgument(0))
                        .entryNumber("JE-202610-000001")
                        .build());
        when(journalEntries.postJournalEntry(any(UUID.class), isNull()))
                .thenAnswer(invocation -> JournalEntryResponse.builder()
                        .journalEntryId(invocation.getArgument(0))
                        .entryNumber("JE-202610-000001")
                        .build());
        GLAccount bank = new GLAccount(BANK);
        bank.setAccountCode("1000");
        bank.setAccountSubtype(AccountSubtype.BANK_CASH);
        bank.setActivationDate(LocalDateTime.of(2020, 1, 1, 0, 0));
        when(glAccounts.findById(BANK)).thenReturn(Optional.of(bank));
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("USD"));
    }

    @Test
    @DisplayName("AC3: go-live posts Dr 1080 / Cr 3900 dated the go-live date, 1080 carrying register and location")
    void goLivePostsAgainstOpeningBalanceEquity() {
        RegisterFloatService.Outcome outcome = service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        JournalEntryCreateRequest entry = capturedEntries().get(0);
        assertThat(entry.getTransactionDate()).isEqualTo(DAY.atStartOfDay());
        assertThat(entry.getSourceEventType()).isEqualTo("REGISTER_FLOAT");
        assertThat(entry.getLines()).hasSize(2);
        assertThat(entry.getLines().get(0).getGlAccountId()).isEqualTo(FLOAT_ACCOUNT);
        assertThat(entry.getLines().get(0).getDebitAmount()).isEqualByComparingTo("200.00");
        assertThat(entry.getLines().get(0).getDimensions())
                .containsEntry("registerId", "T-1")
                .containsEntry("locationId", LOCATION.toString());
        assertThat(entry.getLines().get(1).getGlAccountId()).isEqualTo(EQUITY_ACCOUNT);
        assertThat(entry.getLines().get(1).getCreditAmount()).isEqualByComparingTo("200.00");
        // No override path for a go-live (§4.6 "in an open period").
        verify(journalEntries).postJournalEntry(any(UUID.class), isNull());
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.response().amount()).isEqualByComparingTo("200.00");
        assertThat(outcome.response().previousAmount()).isEqualByComparingTo("0");
        assertThat(outcome.response().journalEntryNumber()).isEqualTo("JE-202610-000001");
        assertThat(registerFloat.getGoLiveJournalEntryId())
                .isEqualTo(outcome.response().journalEntryId());

        ArgumentCaptor<DomainEventEnvelope<?>> fact = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("accounting.events.v1"), fact.capture());
        assertThat(fact.getValue().eventType()).isEqualTo(RegisterFloatChangedV1.EVENT_TYPE);
        RegisterFloatChangedV1 payload =
                (RegisterFloatChangedV1) fact.getValue().payload();
        assertThat(payload.registerId()).isEqualTo("T-1");
        assertThat(payload.kind()).isEqualTo(RegisterFloatChangedV1.Kind.GO_LIVE);
        assertThat(payload.amount()).isEqualByComparingTo("200.00");
        verify(auditLogs).save(any());
    }

    @Test
    @DisplayName("AC4: a second go-live is 409 FLOAT_ALREADY_ESTABLISHED and posts nothing")
    void goLiveIsOncePerRegister() {
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        assertThatThrownBy(() -> service.establishGoLive("T-1", goLive("250.00", UUID.randomUUID())))
                .isInstanceOf(CashSetupException.class)
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_ALREADY_ESTABLISHED);
        assertThat(capturedEntries()).hasSize(1);
    }

    @Test
    @DisplayName(
            "[M] AC4: a register with a standing Change float and no go-live refuses a go-live (any float history)")
    void goLiveIsRefusedAfterAnyFloatHistory() {
        service.changeFloat("T-1", change("100.00", UUID.randomUUID()));
        assertThat(registerFloat.getGoLiveJournalEntryId()).isNull();

        assertThatThrownBy(() -> service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID())))
                .isInstanceOf(CashSetupException.class)
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_ALREADY_ESTABLISHED);
    }

    @Test
    @DisplayName("AC4: once the go-live entry is reversed (no standing history), go-live runs again")
    void goLiveRunsAgainAfterReversal() {
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        // What RegisterFloatReversalReaction leaves after the reversal.
        registerFloat.setGoLiveJournalEntryId(null);
        registerFloat.setAmount(BigDecimal.ZERO);
        standing.clear();

        RegisterFloatService.Outcome again = service.establishGoLive("T-1", goLive("180.00", UUID.randomUUID()));

        assertThat(again.response().amount()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("AC5: 200 -> 300 posts Dr 1080 100 / Cr bank 100; 300 -> 150 posts Dr bank 150 / Cr 1080 150")
    void changePostsTheDifferenceAgainstTheBank() {
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        service.changeFloat("T-1", change("300.00", UUID.randomUUID()));
        JournalEntryCreateRequest increase = capturedEntries().get(1);
        assertThat(increase.getLines().get(0).getGlAccountId()).isEqualTo(FLOAT_ACCOUNT);
        assertThat(increase.getLines().get(0).getDebitAmount()).isEqualByComparingTo("100.00");
        assertThat(increase.getLines().get(1).getGlAccountId()).isEqualTo(BANK);
        assertThat(increase.getLines().get(1).getCreditAmount()).isEqualByComparingTo("100.00");

        RegisterFloatService.Outcome decrease = service.changeFloat("T-1", change("150.00", UUID.randomUUID()));
        JournalEntryCreateRequest entry = capturedEntries().get(2);
        assertThat(entry.getLines().get(0).getGlAccountId()).isEqualTo(BANK);
        assertThat(entry.getLines().get(0).getDebitAmount()).isEqualByComparingTo("150.00");
        assertThat(entry.getLines().get(1).getGlAccountId()).isEqualTo(FLOAT_ACCOUNT);
        assertThat(entry.getLines().get(1).getCreditAmount()).isEqualByComparingTo("150.00");
        assertThat(decrease.response().previousAmount()).isEqualByComparingTo("300.00");
        assertThat(decrease.response().amount()).isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("a command naming another location than the register's is 422 FLOAT_REGISTER_LOCATION_MISMATCH,"
            + " posts nothing and never moves the register")
    void registerStaysAtItsLocation() {
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        UUID elsewhere = UUID.fromString("019a0000-0000-7000-8000-00000000a002");

        assertThatThrownBy(() -> service.changeFloat(
                        "T-1",
                        new RegisterFloatChangeRequest(
                                elsewhere,
                                new BigDecimal("300.00"),
                                BANK,
                                DAY,
                                "More change needed for the weekend",
                                UUID.randomUUID(),
                                null)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_REGISTER_LOCATION_MISMATCH);
        // A reversed go-live leaves the register at its location too.
        registerFloat.setGoLiveJournalEntryId(null);
        registerFloat.setAmount(BigDecimal.ZERO);
        standing.clear();
        assertThatThrownBy(() -> service.establishGoLive(
                        "T-1",
                        new RegisterFloatGoLiveRequest(
                                elsewhere,
                                new BigDecimal("180.00"),
                                DAY,
                                "Counted float in drawer 1 at go-live",
                                UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_REGISTER_LOCATION_MISMATCH);

        assertThat(capturedEntries()).hasSize(1);
        assertThat(registerFloat.getLocationId()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("a change to the current amount is 422 FLOAT_AMOUNT_UNCHANGED")
    void unchangedAmountIsRefused() {
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        assertThatThrownBy(() -> service.changeFloat("T-1", change("200", UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_AMOUNT_UNCHANGED);
    }

    @Test
    @DisplayName("a non-bank, inactive or foreign-currency account is 422 FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE")
    void bankAccountMustBeEligible() {
        when(currencies.currencyOf(BANK)).thenReturn(Optional.of("CAD"));
        assertThatThrownBy(() -> service.changeFloat("T-1", change("100.00", UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE);

        GLAccount drawer = new GLAccount(FLOAT_ACCOUNT);
        drawer.setAccountCode("1080");
        drawer.setAccountSubtype(AccountSubtype.CASH_ON_HAND);
        when(glAccounts.findById(FLOAT_ACCOUNT)).thenReturn(Optional.of(drawer));
        RegisterFloatChangeRequest toDrawer = new RegisterFloatChangeRequest(
                LOCATION,
                new BigDecimal("100.00"),
                FLOAT_ACCOUNT,
                DAY,
                "Topping up the drawer",
                UUID.randomUUID(),
                null);
        assertThatThrownBy(() -> service.changeFloat("T-1", toDrawer))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_BANK_ACCOUNT_NOT_ELIGIBLE);
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("AC6: the same requestId and body returns the first result; another body is 409 IDEMPOTENCY_CONFLICT")
    void idempotentOnRequestId() {
        UUID requestId = UUID.randomUUID();
        service.establishGoLive("T-1", goLive("200.00", requestId));
        RegisterFloatChange first = standing.get(0);
        when(changes.findByRequestId(requestId)).thenReturn(Optional.of(first));
        when(journalEntries.getJournalEntry(first.getJournalEntryId()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202610-000001")
                        .build());

        RegisterFloatService.Outcome replay = service.establishGoLive("T-1", goLive("200.0", requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId()).isEqualTo(first.getJournalEntryId());
        assertThat(capturedEntries()).hasSize(1);
        assertThatThrownBy(() -> service.establishGoLive("T-1", goLive("201.00", requestId)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("a duplicate requestId that waited on the register's lock answers with the first result, not a 409")
    void duplicateThatWaitedOnTheLockIsAReplay() {
        UUID requestId = UUID.randomUUID();
        service.establishGoLive("T-1", goLive("200.00", requestId));
        RegisterFloatChange first = standing.get(0);
        // The second request found no row before the lock (the first had not committed), and finds it after.
        when(changes.findByRequestId(requestId)).thenReturn(Optional.empty(), Optional.of(first));
        when(journalEntries.getJournalEntry(first.getJournalEntryId()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202610-000001")
                        .build());

        RegisterFloatService.Outcome replay = service.establishGoLive("T-1", goLive("200.00", requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId()).isEqualTo(first.getJournalEntryId());
        assertThat(capturedEntries()).hasSize(1);

        UUID changeRequest = UUID.randomUUID();
        service.changeFloat("T-1", change("300.00", changeRequest));
        RegisterFloatChange change = standing.get(standing.size() - 1);
        when(changes.findByRequestId(changeRequest)).thenReturn(Optional.empty(), Optional.of(change));
        when(journalEntries.getJournalEntry(change.getJournalEntryId()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202610-000002")
                        .build());
        assertThat(service.changeFloat("T-1", change("300.00", changeRequest)).replayed())
                .isTrue();
        assertThat(capturedEntries()).hasSize(2);
    }

    @Test
    @DisplayName("a body without a justification of 10 characters, or a finer amount than cents, is 400")
    void requestShapeIsValidated() {
        RegisterFloatGoLiveRequest shortJustification =
                new RegisterFloatGoLiveRequest(LOCATION, new BigDecimal("200.00"), DAY, "too short", UUID.randomUUID());
        assertThatThrownBy(() -> service.establishGoLive("T-1", shortJustification))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establishGoLive("T-1", goLive("200.001", UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
        assertThatThrownBy(() -> service.establishGoLive(" ", goLive("200.00", UUID.randomUUID())))
                .isInstanceOf(InvalidRequestParameterException.class);
    }

    @Test
    @DisplayName("an omitted effective date is today in the tenant's accounting time zone")
    void effectiveDateDefaultsThroughTheZoneResolver() {
        when(zoneResolver.today()).thenReturn(LocalDate.of(2026, 10, 7));

        RegisterFloatService.Outcome outcome = service.changeFloat(
                "T-1",
                new RegisterFloatChangeRequest(
                        LOCATION,
                        new BigDecimal("50.00"),
                        BANK,
                        null,
                        "Funding a new register",
                        UUID.randomUUID(),
                        null));

        assertThat(outcome.response().effectiveDate()).isEqualTo(LocalDate.of(2026, 10, 7));
        assertThat(outcome.response().kind()).isEqualTo(RegisterFloatChangeKind.CHANGE);
        verify(journalEntries).postJournalEntry(any(UUID.class), isNull());
        verify(resolver, never()).resolveGLAccount(anyString(), eq("OPENING_BALANCE_EQUITY"), any(LocalDateTime.class));
    }

    // ---- relocation (#2571, AW32) -----------------------------------------------------------------------------

    @Test
    @DisplayName("AC1: a move posts Dr 1080 {T-1, B} / Cr 1080 {T-1, A} for the float, dated the move; the register"
            + " is at B, the float unchanged, and nothing else posts")
    void relocationPostsAReclass() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        RegisterFloatService.Outcome moved =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, UUID.randomUUID()));

        JournalEntryCreateRequest entry = capturedEntries().get(1);
        assertThat(entry.getTransactionDate()).isEqualTo(MOVE_DAY.atStartOfDay());
        assertThat(entry.getSourceEventType()).isEqualTo("REGISTER_FLOAT");
        assertThat(entry.getLines())
                .hasSize(2)
                .allSatisfy(line -> assertThat(line.getGlAccountId())
                        .as("no bank and no 3900 line")
                        .isEqualTo(FLOAT_ACCOUNT));
        assertThat(entry.getLines().get(0).getDebitAmount()).isEqualByComparingTo("200.00");
        assertThat(entry.getLines().get(0).getDimensions())
                .containsEntry("registerId", "T-1")
                .containsEntry("locationId", SHOP_B.toString());
        assertThat(entry.getLines().get(1).getCreditAmount()).isEqualByComparingTo("200.00");
        assertThat(entry.getLines().get(1).getDimensions())
                .containsEntry("registerId", "T-1")
                .containsEntry("locationId", LOCATION.toString());
        verify(resolver).resolveGLAccount("REGISTER_FLOAT", "REGISTER_FLOAT", MOVE_DAY.atStartOfDay());

        assertThat(registerFloat.getLocationId()).isEqualTo(SHOP_B);
        assertThat(registerFloat.getAmount()).isEqualByComparingTo("200.00");
        assertThat(moved.replayed()).isFalse();
        assertThat(moved.response().kind()).isEqualTo(RegisterFloatChangeKind.RELOCATION);
        assertThat(moved.response().locationId()).isEqualTo(SHOP_B);
        assertThat(moved.response().previousAmount()).isEqualByComparingTo("200.00");
        assertThat(moved.response().amount()).isEqualByComparingTo("200.00");
        assertThat(moved.response().effectiveDate()).isEqualTo(MOVE_DAY);
        assertThat(moved.response().journalEntryNumber()).isEqualTo("JE-202610-000001");

        // AC11: the history row.
        RegisterFloatChange row = standing.get(standing.size() - 1);
        assertThat(row.getKind()).isEqualTo(RegisterFloatChangeKind.RELOCATION);
        assertThat(row.getPreviousLocationId()).isEqualTo(LOCATION);
        assertThat(row.getLocationId()).isEqualTo(SHOP_B);
        assertThat(row.getReason()).isEqualTo(RegisterFloatRelocationReason.MOVED);
        assertThat(row.getPreviousAmount()).isEqualByComparingTo(row.getNewAmount());
        assertThat(row.getJournalEntryId()).isEqualTo(moved.response().journalEntryId());
        assertThat(row.getJustification()).isEqualTo("Drawer 1 moved to the new shop");
        assertThat(row.getActor()).isEqualTo("SYSTEM");
        assertThat(row.getRequestId()).isNotNull();

        // AC11: the audit row names both locations, the amount and the reason.
        ArgumentCaptor<AccountingAuditLog> audits = ArgumentCaptor.forClass(AccountingAuditLog.class);
        verify(auditLogs, org.mockito.Mockito.times(2)).save(audits.capture());
        AccountingAuditLog audit = audits.getAllValues().get(1);
        assertThat(audit.getOperation()).isEqualTo("REGISTER_FLOAT_RELOCATION");
        assertThat(audit.getNewValue())
                .contains("fromLocationId=" + LOCATION)
                .contains("toLocationId=" + SHOP_B)
                .contains("amount=200")
                .contains("reason=MOVED");

        // AC12: the fact.
        ArgumentCaptor<DomainEventEnvelope<?>> facts = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, org.mockito.Mockito.times(2)).publish(eq("accounting.events.v1"), facts.capture());
        DomainEventEnvelope<?> envelope = facts.getAllValues().get(1);
        assertThat(envelope.schemaVersion()).isEqualTo(2);
        RegisterFloatChangedV1 fact = (RegisterFloatChangedV1) envelope.payload();
        assertThat(fact.kind()).isEqualTo(RegisterFloatChangedV1.Kind.RELOCATION);
        assertThat(fact.locationId()).isEqualTo(SHOP_B);
        assertThat(fact.previousLocationId()).isEqualTo(LOCATION);
        assertThat(fact.amount()).isEqualByComparingTo(fact.previousAmount());
        assertThat(fact.journalEntryId()).isEqualTo(moved.response().journalEntryId());
        RegisterFloatChangedV1 goLiveFact =
                (RegisterFloatChangedV1) facts.getAllValues().get(0).payload();
        assertThat(goLiveFact.previousLocationId()).isNull();
    }

    @Test
    @DisplayName("AC1: reason ENTERED_IN_ERROR posts the identical entry")
    void enteredInErrorPostsTheSameEntry() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        service.relocate(
                "T-1", relocation(RegisterFloatRelocationReason.ENTERED_IN_ERROR, MOVE_DAY, UUID.randomUUID()));

        JournalEntryCreateRequest entry = capturedEntries().get(1);
        assertThat(entry.getLines().get(0).getDimensions()).containsEntry("locationId", SHOP_B.toString());
        assertThat(entry.getLines().get(0).getDebitAmount()).isEqualByComparingTo("200.00");
        assertThat(entry.getLines().get(1).getDimensions()).containsEntry("locationId", LOCATION.toString());
        assertThat(entry.getLines().get(1).getCreditAmount()).isEqualByComparingTo("200.00");
        assertThat(standing.get(standing.size() - 1).getReason())
                .isEqualTo(RegisterFloatRelocationReason.ENTERED_IN_ERROR);
    }

    @Test
    @DisplayName("AC7: the move posts through the standard period gate, with the caller's override justification")
    void relocationTakesTheStandardGate() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        service.relocate(
                "T-1",
                new RegisterFloatRelocationRequest(
                        LOCATION,
                        SHOP_B,
                        RegisterFloatRelocationReason.MOVED,
                        MOVE_DAY,
                        "Drawer 1 moved to the new shop",
                        UUID.randomUUID(),
                        "  Moved on the last day of the closed month "));

        verify(journalEntries).postJournalEntry(any(UUID.class), eq("Moved on the last day of the closed month"));
    }

    @Test
    @DisplayName("AC3/AC4: fromLocationId other than the register's is 422 FLOAT_REGISTER_LOCATION_MISMATCH, which"
            + " never names the register's location; toLocationId = fromLocationId is 422"
            + " FLOAT_RELOCATION_SAME_LOCATION; nothing posts")
    void locationChecks() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        UUID elsewhere = UUID.fromString("019a0000-0000-7000-8000-00000000a003");

        assertThatThrownBy(() -> service.relocate(
                        "T-1",
                        new RegisterFloatRelocationRequest(
                                elsewhere,
                                SHOP_B,
                                RegisterFloatRelocationReason.MOVED,
                                MOVE_DAY,
                                "Drawer 1 moved to the new shop",
                                UUID.randomUUID(),
                                null)))
                .isInstanceOf(CashSetupException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(LOCATION.toString()))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_REGISTER_LOCATION_MISMATCH);
        assertThatThrownBy(() -> service.relocate(
                        "T-1",
                        new RegisterFloatRelocationRequest(
                                LOCATION,
                                LOCATION,
                                RegisterFloatRelocationReason.MOVED,
                                MOVE_DAY,
                                "Drawer 1 moved to the new shop",
                                UUID.randomUUID(),
                                null)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_SAME_LOCATION);

        assertThat(capturedEntries()).hasSize(1);
        assertThat(registerFloat.getLocationId()).isEqualTo(LOCATION);
    }

    @Test
    @DisplayName("AC5: no float row is 404 FLOAT_REGISTER_NOT_FOUND; a negative float is 422 FLOAT_AMOUNT_NEGATIVE")
    void rowsAndAmounts() {
        when(zoneResolver.today()).thenReturn(TODAY);
        RegisterFloatRelocationRequest move =
                relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, UUID.randomUUID());

        assertThatThrownBy(() -> service.relocate("T-1", move))
                .isInstanceOf(CashSetupException.class)
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_REGISTER_NOT_FOUND);
        assertThat(CashSetupException.Code.FLOAT_REGISTER_NOT_FOUND.status().value())
                .isEqualTo(404);
        verify(floats, never()).saveAndFlush(any());

        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        // What a reversal of an entry that later changes relied on leaves behind.
        registerFloat.setAmount(new BigDecimal("-50.00"));
        assertThatThrownBy(() -> service.relocate("T-1", move))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_AMOUNT_NEGATIVE);
        assertThat(capturedEntries()).hasSize(1);
    }

    @Test
    @DisplayName("AC5: a zero float moves with no entry; the history row and the fact carry journalEntryId null; a"
            + " go-live at B is then accepted")
    void zeroFloatMovesWithoutAnEntry() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        // What RegisterFloatReversalReaction leaves after the go-live is reversed.
        standing.get(0).setReversalJournalEntryId(UUID.randomUUID());
        registerFloat.setGoLiveJournalEntryId(null);
        registerFloat.setAmount(BigDecimal.ZERO);

        RegisterFloatService.Outcome moved = service.relocate(
                "T-1", relocation(RegisterFloatRelocationReason.ENTERED_IN_ERROR, DAY, UUID.randomUUID()));

        assertThat(capturedEntries()).hasSize(1);
        verify(journalEntries).postJournalEntry(any(UUID.class), isNull());
        assertThat(moved.response().journalEntryId()).isNull();
        assertThat(moved.response().journalEntryNumber()).isNull();
        assertThat(standing.get(standing.size() - 1).getJournalEntryId()).isNull();
        ArgumentCaptor<DomainEventEnvelope<?>> facts = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, org.mockito.Mockito.times(2)).publish(eq("accounting.events.v1"), facts.capture());
        RegisterFloatChangedV1 fact =
                (RegisterFloatChangedV1) facts.getAllValues().get(1).payload();
        assertThat(fact.journalEntryId()).isNull();
        assertThat(fact.kind()).isEqualTo(RegisterFloatChangedV1.Kind.RELOCATION);

        RegisterFloatService.Outcome again = service.establishGoLive(
                "T-1",
                new RegisterFloatGoLiveRequest(
                        SHOP_B,
                        new BigDecimal("200.00"),
                        DAY,
                        "Counted float in drawer 1 at go-live",
                        UUID.randomUUID()));
        assertThat(again.response().locationId()).isEqualTo(SHOP_B);
        assertThat(capturedEntries().get(1).getLines().get(0).getDimensions())
                .containsEntry("locationId", SHOP_B.toString());
    }

    @Test
    @DisplayName("AC6: the date defaults to today; after today, or before the latest standing go-live, change,"
            + " relocation or reversal, is 422 FLOAT_RELOCATION_DATE_INVALID")
    void relocationDates() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));

        assertThatThrownBy(() -> service.relocate(
                        "T-1", relocation(RegisterFloatRelocationReason.MOVED, TODAY.plusDays(1), UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_DATE_INVALID);
        assertThatThrownBy(() -> service.relocate(
                        "T-1", relocation(RegisterFloatRelocationReason.MOVED, DAY.minusDays(1), UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_DATE_INVALID);
        assertThat(capturedEntries()).hasSize(1);

        RegisterFloatService.Outcome moved =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, null, UUID.randomUUID()));
        assertThat(moved.response().effectiveDate()).isEqualTo(TODAY);

        // A move back may not predate the first move.
        assertThatThrownBy(() -> service.relocate(
                        "T-1",
                        new RegisterFloatRelocationRequest(
                                SHOP_B,
                                LOCATION,
                                RegisterFloatRelocationReason.MOVED,
                                TODAY.minusDays(1),
                                "Drawer 1 went back to the old shop",
                                UUID.randomUUID(),
                                null)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_DATE_INVALID);

        // A reversal posts on 1080 too: a later move may not predate it.
        RegisterFloatChange reversal = new RegisterFloatChange();
        reversal.setKind(RegisterFloatChangeKind.REVERSAL);
        reversal.setEffectiveDate(TODAY);
        standing.add(reversal);
        standing.removeIf(change -> change.getKind() == RegisterFloatChangeKind.RELOCATION);
        registerFloat.setLocationId(LOCATION);
        assertThatThrownBy(() -> service.relocate(
                        "T-1", relocation(RegisterFloatRelocationReason.MOVED, TODAY.minusDays(1), UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_DATE_INVALID);
    }

    @Test
    @DisplayName(
            "AC2/AC6: a reversed entry still floors the move: go-live 200 at A on 10-01, change to 300 dated 10-10,"
                    + " that change reversed dated 10-05; a move dated 10-07 is 422 FLOAT_RELOCATION_DATE_INVALID, one dated"
                    + " 10-10 moves")
    void reversedEntryStillFloorsTheMove() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        service.changeFloat(
                "T-1",
                new RegisterFloatChangeRequest(
                        LOCATION,
                        new BigDecimal("300.00"),
                        BANK,
                        LocalDate.of(2026, 10, 10),
                        "More change needed for the weekend",
                        UUID.randomUUID(),
                        null));
        // What RegisterFloatReversalReaction leaves after the change is reversed with a date before the change.
        RegisterFloatChange change = standing.get(1);
        change.setReversalJournalEntryId(UUID.randomUUID());
        RegisterFloatChange reversal = new RegisterFloatChange();
        reversal.setKind(RegisterFloatChangeKind.REVERSAL);
        reversal.setEffectiveDate(LocalDate.of(2026, 10, 5));
        standing.add(reversal);
        registerFloat.setAmount(new BigDecimal("200.00"));

        assertThatThrownBy(() -> service.relocate(
                        "T-1",
                        relocation(RegisterFloatRelocationReason.MOVED, LocalDate.of(2026, 10, 7), UUID.randomUUID())))
                .isInstanceOf(CashSetupException.class)
                .satisfies(e -> assertThat(e.getMessage()).contains("2026-10-10"))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_DATE_INVALID);
        assertThat(capturedEntries()).hasSize(2);

        RegisterFloatService.Outcome moved = service.relocate(
                "T-1", relocation(RegisterFloatRelocationReason.MOVED, LocalDate.of(2026, 10, 10), UUID.randomUUID()));
        assertThat(moved.response().amount()).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("AC6: a go-live or Change float dated before the latest relocation is 422"
            + " FLOAT_DATE_BEFORE_RELOCATION")
    void laterCommandsMayNotPredateAMove() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, UUID.randomUUID()));

        assertThatThrownBy(() -> service.changeFloat(
                        "T-1",
                        new RegisterFloatChangeRequest(
                                SHOP_B,
                                new BigDecimal("300.00"),
                                BANK,
                                MOVE_DAY.minusDays(1),
                                "More change needed for the weekend",
                                UUID.randomUUID(),
                                null)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_DATE_BEFORE_RELOCATION);
        RegisterFloatService.Outcome onTheDay = service.changeFloat(
                "T-1",
                new RegisterFloatChangeRequest(
                        SHOP_B,
                        new BigDecimal("300.00"),
                        BANK,
                        MOVE_DAY,
                        "More change needed for the weekend",
                        UUID.randomUUID(),
                        null));
        assertThat(onTheDay.response().amount()).isEqualByComparingTo("300.00");

        // After the float is gone (both entries reversed), a go-live may not predate the move either.
        standing.forEach(change -> change.setReversalJournalEntryId(UUID.randomUUID()));
        registerFloat.setGoLiveJournalEntryId(null);
        registerFloat.setAmount(BigDecimal.ZERO);
        standing.add(relocationRow(MOVE_DAY));
        assertThatThrownBy(() -> service.establishGoLive(
                        "T-1",
                        new RegisterFloatGoLiveRequest(
                                SHOP_B,
                                new BigDecimal("200.00"),
                                MOVE_DAY.minusDays(1),
                                "Counted float in drawer 1 at go-live",
                                UUID.randomUUID())))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_DATE_BEFORE_RELOCATION);
    }

    @Test
    @DisplayName("AC10: the same requestId and body answers with the first result, re-checked after the row lock;"
            + " another body is 409 IDEMPOTENCY_CONFLICT")
    void relocationIsIdempotent() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        UUID requestId = UUID.randomUUID();
        RegisterFloatService.Outcome first =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));
        RegisterFloatChange row = standing.get(standing.size() - 1);
        // The duplicate found no row before the lock (the first had not committed) and finds it after.
        when(changes.findByRequestId(requestId)).thenReturn(Optional.empty(), Optional.of(row));
        when(journalEntries.getJournalEntry(row.getJournalEntryId()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202610-000001")
                        .build());

        RegisterFloatService.Outcome replay =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId())
                .isEqualTo(first.response().journalEntryId());
        assertThat(replay.response().locationId()).isEqualTo(SHOP_B);
        assertThat(capturedEntries()).hasSize(2);
        when(changes.findByRequestId(requestId)).thenReturn(Optional.of(row));
        assertThatThrownBy(() -> service.relocate(
                        "T-1", relocation(RegisterFloatRelocationReason.ENTERED_IN_ERROR, MOVE_DAY, requestId)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.IDEMPOTENCY_CONFLICT);
    }

    @Test
    @DisplayName("AC10: a replayed move of a zero float answers with the first result and no entry number")
    void zeroFloatReplay() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        standing.get(0).setReversalJournalEntryId(UUID.randomUUID());
        registerFloat.setAmount(BigDecimal.ZERO);
        UUID requestId = UUID.randomUUID();
        service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));
        RegisterFloatChange row = standing.get(standing.size() - 1);
        when(changes.findByRequestId(requestId)).thenReturn(Optional.of(row));

        RegisterFloatService.Outcome replay =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId()).isNull();
        assertThat(replay.response().journalEntryNumber()).isNull();
        verify(journalEntries, never()).getJournalEntry(any());
    }

    @Test
    @DisplayName("AC11: a missing reason, the reversal follow-up reason, or a justification under 10 characters is 400")
    void relocationRequestIsValidated() {
        UUID requestId = UUID.randomUUID();
        for (RegisterFloatRelocationRequest invalid : List.of(
                new RegisterFloatRelocationRequest(
                        LOCATION, SHOP_B, null, MOVE_DAY, "Drawer 1 moved to the new shop", requestId, null),
                new RegisterFloatRelocationRequest(
                        LOCATION,
                        SHOP_B,
                        RegisterFloatRelocationReason.REVERSAL_FOLLOW_UP,
                        MOVE_DAY,
                        "Drawer 1 moved to the new shop",
                        requestId,
                        null),
                new RegisterFloatRelocationRequest(
                        LOCATION, SHOP_B, RegisterFloatRelocationReason.MOVED, MOVE_DAY, "too short", requestId, null),
                new RegisterFloatRelocationRequest(
                        null,
                        SHOP_B,
                        RegisterFloatRelocationReason.MOVED,
                        MOVE_DAY,
                        "Drawer 1 moved to the new shop",
                        requestId,
                        null),
                new RegisterFloatRelocationRequest(
                        LOCATION,
                        null,
                        RegisterFloatRelocationReason.MOVED,
                        MOVE_DAY,
                        "Drawer 1 moved to the new shop",
                        requestId,
                        null))) {
            assertThatThrownBy(() -> service.relocate("T-1", invalid))
                    .isInstanceOf(InvalidRequestParameterException.class);
        }
        verify(floats, never()).lockByRegisterId(any());
    }

    @Test
    @DisplayName("#2573: an open session on the register is 422 FLOAT_REGISTER_SESSION_OPEN naming the session, never"
            + " its location, for either reason and with an override; nothing posts and no fact is queued")
    void openSessionBlocksTheMove() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        ExtOrderRegisterSession open = new ExtOrderRegisterSession();
        open.setSessionId(UUID.fromString("019a0000-0000-7000-8000-0000000000c1"));
        open.setTerminalId("T-1");
        open.setLocationId(LOCATION);
        open.setStatus(RegisterSessionStatus.OPEN);
        open.setOpenedAt(java.time.Instant.parse("2026-10-20T08:00:00Z"));
        when(sessions.openSessionOf("T-1")).thenReturn(Optional.of(open));
        int rowsBefore = standing.size();

        for (RegisterFloatRelocationReason reason :
                List.of(RegisterFloatRelocationReason.MOVED, RegisterFloatRelocationReason.ENTERED_IN_ERROR)) {
            RegisterFloatRelocationRequest overridden = new RegisterFloatRelocationRequest(
                    LOCATION,
                    SHOP_B,
                    reason,
                    MOVE_DAY,
                    "Drawer 1 moved to the new shop",
                    UUID.randomUUID(),
                    "Moved on the last day of the closed month");
            assertThatThrownBy(() -> service.relocate("T-1", overridden))
                    .isInstanceOf(CashSetupException.class)
                    .satisfies(e -> {
                        CashSetupException refused = (CashSetupException) e;
                        assertThat(refused.getCode()).isEqualTo(CashSetupException.Code.FLOAT_REGISTER_SESSION_OPEN);
                        assertThat(refused.getCode().status().value()).isEqualTo(422);
                        assertThat(refused.getReferenceId())
                                .isEqualTo(open.getSessionId().toString());
                        assertThat(refused.getMessage())
                                .contains("2026-10-20T08:00:00Z")
                                .doesNotContain(LOCATION.toString());
                    });
        }

        assertThat(capturedEntries()).hasSize(1);
        assertThat(standing).hasSize(rowsBefore);
        assertThat(registerFloat.getLocationId()).isEqualTo(LOCATION);
        verify(writer, org.mockito.Mockito.times(1)).publish(any(), any());
    }

    @Test
    @DisplayName("#2573: a replay of a move that succeeded answers 200 with the first result after a session opened")
    void replayIgnoresALaterSession() {
        when(zoneResolver.today()).thenReturn(TODAY);
        service.establishGoLive("T-1", goLive("200.00", UUID.randomUUID()));
        UUID requestId = UUID.randomUUID();
        RegisterFloatService.Outcome first =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));
        RegisterFloatChange row = standing.get(standing.size() - 1);
        ExtOrderRegisterSession open = new ExtOrderRegisterSession();
        open.setSessionId(UUID.randomUUID());
        open.setStatus(RegisterSessionStatus.OPEN);
        open.setOpenedAt(java.time.Instant.parse("2026-10-20T08:00:00Z"));
        when(sessions.openSessionOf("T-1")).thenReturn(Optional.of(open));
        // The duplicate passes the pre-lock check and finds the first row only after the lock.
        when(changes.findByRequestId(requestId)).thenReturn(Optional.empty(), Optional.of(row));
        when(journalEntries.getJournalEntry(row.getJournalEntryId()))
                .thenReturn(JournalEntryResponse.builder()
                        .entryNumber("JE-202610-000001")
                        .build());

        RegisterFloatService.Outcome replay =
                service.relocate("T-1", relocation(RegisterFloatRelocationReason.MOVED, MOVE_DAY, requestId));

        assertThat(replay.replayed()).isTrue();
        assertThat(replay.response().journalEntryId())
                .isEqualTo(first.response().journalEntryId());
        verify(sessions, org.mockito.Mockito.times(1)).openSessionOf("T-1");
    }

    private static RegisterFloatRelocationRequest relocation(
            RegisterFloatRelocationReason reason, LocalDate date, UUID requestId) {
        return new RegisterFloatRelocationRequest(
                LOCATION, SHOP_B, reason, date, "Drawer 1 moved to the new shop", requestId, null);
    }

    private static RegisterFloatChange relocationRow(LocalDate date) {
        RegisterFloatChange row = new RegisterFloatChange();
        row.setKind(RegisterFloatChangeKind.RELOCATION);
        row.setEffectiveDate(date);
        return row;
    }

    private List<JournalEntryCreateRequest> capturedEntries() {
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntries, org.mockito.Mockito.atLeast(0)).createJournalEntry(captor.capture());
        return captor.getAllValues();
    }

    private static RegisterFloatGoLiveRequest goLive(String amount, UUID requestId) {
        return new RegisterFloatGoLiveRequest(
                LOCATION, new BigDecimal(amount), DAY, "Counted float in drawer 1 at go-live", requestId);
    }

    private static RegisterFloatChangeRequest change(String amount, UUID requestId) {
        return new RegisterFloatChangeRequest(
                LOCATION, new BigDecimal(amount), BANK, DAY, "More change needed for the weekend", requestId, null);
    }
}
