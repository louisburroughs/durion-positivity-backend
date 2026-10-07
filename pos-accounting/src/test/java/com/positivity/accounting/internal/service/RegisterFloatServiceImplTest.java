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
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.AccountSubtype;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
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

    private final RegisterFloatRepository floats = mock(RegisterFloatRepository.class);
    private final RegisterFloatChangeRepository changes = mock(RegisterFloatChangeRepository.class);
    private final GLAccountRepository glAccounts = mock(GLAccountRepository.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);
    private final BankAccountCurrencies currencies = mock(BankAccountCurrencies.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
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
                facts);

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
                .thenAnswer(invocation -> List.copyOf(standing));
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
