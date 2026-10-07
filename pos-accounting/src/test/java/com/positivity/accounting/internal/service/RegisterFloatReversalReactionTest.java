package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.entity.RegisterFloatChange;
import com.positivity.accounting.internal.enums.RegisterFloatChangeKind;
import com.positivity.accounting.internal.enums.RegisterFloatRelocationReason;
import com.positivity.accounting.internal.event.LedgerReversalApplied;
import com.positivity.accounting.internal.exception.CashSetupException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.RegisterFloatChangedV1;
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
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

@DisplayName("Register float reversal reaction after a relocation (#2571, AW32)")
class RegisterFloatReversalReactionTest {

    private static final UUID SHOP_A = UUID.fromString("019a0000-0000-7000-8000-00000000a001");
    private static final UUID SHOP_B = UUID.fromString("019a0000-0000-7000-8000-00000000a002");
    private static final UUID FLOAT_ACCOUNT = UUID.fromString("019a0000-0000-7000-8000-000000001080");
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 10, 1);
    private static final LocalDate MOVED = LocalDate.of(2026, 10, 15);

    private final RegisterFloatRepository floats = mock(RegisterFloatRepository.class);
    private final RegisterFloatChangeRepository changes = mock(RegisterFloatChangeRepository.class);
    private final AccountingAuditLogRepository auditLogs = mock(AccountingAuditLogRepository.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final GLMappingResolver resolver = mock(GLMappingResolver.class);
    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final List<RegisterFloatChange> rows = new ArrayList<>();
    private RegisterFloat registerFloat;
    private RegisterFloatChange goLive;
    private RegisterFloatReversalReaction reaction;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<OutboxEventWriter> writers = mock(ObjectProvider.class);
        when(writers.getIfAvailable()).thenReturn(writer);
        RegisterFloatFacts facts = new RegisterFloatFacts(
                writers,
                Clock.fixed(Instant.parse("2026-10-20T12:00:00Z"), ZoneOffset.UTC),
                mock(ObjectProvider.class));
        reaction = new RegisterFloatReversalReaction(floats, changes, auditLogs, facts, journalEntries, resolver);

        // T-1: go-live 200 at A on 10-01, moved to B on 10-15.
        registerFloat = new RegisterFloat();
        registerFloat.setRegisterFloatId(UUID.randomUUID());
        registerFloat.setRegisterId("T-1");
        registerFloat.setLocationId(SHOP_B);
        registerFloat.setAmount(new BigDecimal("200.00"));
        goLive = row(RegisterFloatChangeKind.GO_LIVE, SHOP_A, "0", "200.00", GO_LIVE);
        RegisterFloatChange move = row(RegisterFloatChangeKind.RELOCATION, SHOP_B, "200.00", "200.00", MOVED);
        move.setPreviousLocationId(SHOP_A);
        move.setReason(RegisterFloatRelocationReason.MOVED);
        rows.add(goLive);
        rows.add(move);

        when(floats.lockById(registerFloat.getRegisterFloatId())).thenReturn(Optional.of(registerFloat));
        when(floats.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(changes.findByJournalEntryIdAndKindIn(any(), anyCollection())).thenAnswer(invocation -> {
            UUID entry = invocation.getArgument(0);
            Collection<RegisterFloatChangeKind> kinds = invocation.getArgument(1);
            return rows.stream()
                    .filter(row -> entry.equals(row.getJournalEntryId()) && kinds.contains(row.getKind()))
                    .findFirst();
        });
        when(changes.findByRegisterFloatIdAndKindInAndReversalJournalEntryIdIsNull(any(), anyCollection()))
                .thenAnswer(invocation -> {
                    Collection<RegisterFloatChangeKind> kinds = invocation.getArgument(1);
                    return rows.stream()
                            .filter(row -> kinds.contains(row.getKind()) && row.getReversalJournalEntryId() == null)
                            .toList();
                });
        when(changes.saveAndFlush(any())).thenAnswer(invocation -> {
            RegisterFloatChange saved = invocation.getArgument(0);
            if (saved.getChangeId() == null) {
                // What the UUIDv7 generator does on insert.
                saved.setChangeId(UUID.randomUUID());
                rows.add(saved);
            }
            return saved;
        });
        when(resolver.resolveGLAccount(eq("REGISTER_FLOAT"), eq("REGISTER_FLOAT"), any(LocalDateTime.class)))
                .thenReturn(FLOAT_ACCOUNT);
        when(journalEntries.createJournalEntry(any()))
                .thenAnswer(invocation -> JournalEntryResponse.builder()
                        .journalEntryId(UUID.randomUUID())
                        .build());
        when(journalEntries.postJournalEntry(any(UUID.class), any()))
                .thenAnswer(invocation -> JournalEntryResponse.builder()
                        .journalEntryId(invocation.getArgument(0))
                        .entryNumber("JE-202610-000009")
                        .build());
    }

    @Test
    @DisplayName("AC8: reversing a relocation entry is 409 FLOAT_RELOCATION_NOT_REVERSIBLE and changes nothing")
    void relocationEntryIsNotReversible() {
        RegisterFloatChange move = rows.get(1);

        assertThatThrownBy(() -> reaction.onReversed(reversal(move.getJournalEntryId(), MOVED.plusDays(1), null)))
                .isInstanceOf(CashSetupException.class)
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_RELOCATION_NOT_REVERSIBLE);
        assertThat(CashSetupException.Code.FLOAT_RELOCATION_NOT_REVERSIBLE
                        .status()
                        .value())
                .isEqualTo(409);
        verify(changes, never()).saveAndFlush(any());
        verify(journalEntries, never()).createJournalEntry(any());
        verify(writer, never()).publish(any(), any());
    }

    @Test
    @DisplayName("AC9: a go-live reversal dated before the register's latest move is 422"
            + " FLOAT_REVERSAL_BEFORE_RELOCATION and changes nothing")
    void reversalMayNotPredateTheMove() {
        assertThatThrownBy(() -> reaction.onReversed(reversal(goLive.getJournalEntryId(), MOVED.minusDays(1), null)))
                .extracting(e -> ((CashSetupException) e).getCode())
                .isEqualTo(CashSetupException.Code.FLOAT_REVERSAL_BEFORE_RELOCATION);
        assertThat(goLive.getReversalJournalEntryId()).isNull();
        assertThat(registerFloat.getAmount()).isEqualByComparingTo("200.00");
        verify(changes, never()).saveAndFlush(any());
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("AC9: reversing the pre-move go-live also posts, dated the reversal, Dr 1080 {A} / Cr 1080 {B} under"
            + " the reversal's override, and writes a REVERSAL_FOLLOW_UP relocation row")
    void preMoveReversalReclassesToTheCurrentLocation() {
        LocalDate reversedOn = LocalDate.of(2026, 10, 20);
        LedgerReversalApplied reversed =
                reversal(goLive.getJournalEntryId(), reversedOn, "Reversed in the closed month on audit advice");

        reaction.onReversed(reversed);

        assertThat(registerFloat.getAmount()).isEqualByComparingTo("0");
        assertThat(registerFloat.getLocationId()).isEqualTo(SHOP_B);
        ArgumentCaptor<JournalEntryCreateRequest> entries = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntries).createJournalEntry(entries.capture());
        JournalEntryCreateRequest reclass = entries.getValue();
        assertThat(reclass.getTransactionDate()).isEqualTo(reversedOn.atStartOfDay());
        assertThat(reclass.getSourceEventType()).isEqualTo("REGISTER_FLOAT");
        assertThat(reclass.getLines()).hasSize(2);
        assertThat(reclass.getLines().get(0).getGlAccountId()).isEqualTo(FLOAT_ACCOUNT);
        assertThat(reclass.getLines().get(0).getDebitAmount()).isEqualByComparingTo("200.00");
        assertThat(reclass.getLines().get(0).getDimensions()).containsEntry("locationId", SHOP_A.toString());
        assertThat(reclass.getLines().get(1).getCreditAmount()).isEqualByComparingTo("200.00");
        assertThat(reclass.getLines().get(1).getDimensions()).containsEntry("locationId", SHOP_B.toString());
        verify(journalEntries).postJournalEntry(any(UUID.class), eq("Reversed in the closed month on audit advice"));

        RegisterFloatChange followUp = rows.stream()
                .filter(row -> row.getReason() == RegisterFloatRelocationReason.REVERSAL_FOLLOW_UP)
                .findFirst()
                .orElseThrow();
        assertThat(followUp.getKind()).isEqualTo(RegisterFloatChangeKind.RELOCATION);
        assertThat(followUp.getPreviousLocationId()).isEqualTo(SHOP_A);
        assertThat(followUp.getLocationId()).isEqualTo(SHOP_B);
        assertThat(followUp.getPreviousAmount()).isEqualByComparingTo(followUp.getNewAmount());
        assertThat(followUp.getEffectiveDate()).isEqualTo(reversedOn);
        assertThat(followUp.getJournalEntryId()).isNotNull();
        assertThat(followUp.getOverrideJustification()).isEqualTo("Reversed in the closed month on audit advice");
        assertThat(rows.get(rows.size() - 1).getKind())
                .as("the REVERSAL row is the latest, so a republish names the reversal")
                .isEqualTo(RegisterFloatChangeKind.REVERSAL);
        verify(auditLogs, times(2)).save(any());

        // One fact: the reversal. The follow-up does not move the register.
        ArgumentCaptor<DomainEventEnvelope<?>> facts = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("accounting.events.v1"), facts.capture());
        RegisterFloatChangedV1 fact = (RegisterFloatChangedV1) facts.getValue().payload();
        assertThat(fact.kind()).isEqualTo(RegisterFloatChangedV1.Kind.REVERSAL);
        assertThat(fact.locationId()).isEqualTo(SHOP_B);
        assertThat(fact.previousLocationId()).isNull();
    }

    @Test
    @DisplayName("a change made at the current location after the move reverses with no reclass")
    void postMoveReversalPostsNoReclass() {
        RegisterFloatChange change = row(RegisterFloatChangeKind.CHANGE, SHOP_B, "200.00", "300.00", MOVED.plusDays(1));
        rows.add(change);
        registerFloat.setAmount(new BigDecimal("300.00"));

        reaction.onReversed(reversal(change.getJournalEntryId(), MOVED.plusDays(2), null));

        assertThat(registerFloat.getAmount()).isEqualByComparingTo("200.00");
        verify(journalEntries, never()).createJournalEntry(any());
        assertThat(rows).noneMatch(row -> row.getReason() == RegisterFloatRelocationReason.REVERSAL_FOLLOW_UP);
    }

    private RegisterFloatChange row(
            RegisterFloatChangeKind kind, UUID location, String previous, String next, LocalDate date) {
        RegisterFloatChange row = new RegisterFloatChange();
        row.setChangeId(UUID.randomUUID());
        row.setRegisterFloatId(registerFloat.getRegisterFloatId());
        row.setRegisterId("T-1");
        row.setLocationId(location);
        row.setKind(kind);
        row.setPreviousAmount(new BigDecimal(previous));
        row.setNewAmount(new BigDecimal(next));
        row.setJournalEntryId(UUID.randomUUID());
        row.setEffectiveDate(date);
        return row;
    }

    private static LedgerReversalApplied reversal(UUID original, LocalDate date, String override) {
        return new LedgerReversalApplied(
                original, UUID.randomUUID(), date, List.of(UUID.randomUUID()), Set.of(FLOAT_ACCOUNT), "cfo", override);
    }
}
