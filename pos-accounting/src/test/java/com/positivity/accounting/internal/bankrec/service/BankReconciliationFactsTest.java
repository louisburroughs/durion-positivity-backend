package com.positivity.accounting.internal.bankrec.service;

import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.ACCOUNT_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.END;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.RECON_ID;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.START;
import static com.positivity.accounting.internal.bankrec.service.BankRecFixtures.reconciliation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import com.positivity.accounting.internal.bankrec.enums.InvalidationReason;
import com.positivity.accounting.internal.config.OutboxEventWriter;
import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.accounting.BankReconciliationApprovedV1;
import com.positivity.domainevents.accounting.BankReconciliationCancelledV1;
import com.positivity.domainevents.accounting.BankReconciliationInvalidatedV1;
import com.positivity.domainevents.accounting.BankReconciliationSubmittedV1;
import com.positivity.domainevents.accounting.BankReconciliationSupersededV1;
import com.positivity.shared.id.UUIDv7Generator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/**
 * {@link BankReconciliationFacts} (SPEC §3.10; story S5, #2304): the five envelopes on {@code accounting.events.v1},
 * {@code schemaVersion} 1, keyed by the reconciliation, with the payloads §3.10 names and the currency of every
 * amount (ADR-0067).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("BankReconciliationFacts — outbox envelopes (#2304)")
class BankReconciliationFactsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ObjectProvider<OutboxEventWriter> provider;

    @Mock
    private OutboxEventWriter writer;

    private BankReconciliationFacts facts;
    private BankReconciliation recon;

    @BeforeEach
    void setUp() {
        facts = new BankReconciliationFacts(provider, clock);
        recon = reconciliation();
        recon.setDifference(new BigDecimal("0.0100"));
        recon.setCountUnexplainedBank(0);
        recon.setCountUnexplainedLedger(0);
        recon.setApprovedGlEndingBalance(new BigDecimal("1234.5600"));
        recon.setAdjustedBankBalance(new BigDecimal("1234.5700"));
    }

    @SuppressWarnings("unchecked")
    private DomainEventEnvelope<Object> published() {
        ArgumentCaptor<DomainEventEnvelope<?>> envelope = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(org.mockito.ArgumentMatchers.eq("accounting.events.v1"), envelope.capture());
        DomainEventEnvelope<Object> captured = (DomainEventEnvelope<Object>) envelope.getValue();
        assertThat(captured.schemaVersion()).isEqualTo(1);
        assertThat(captured.aggregateId()).isEqualTo(RECON_ID);
        assertThat(captured.sourceService()).isEqualTo("pos-accounting");
        return captured;
    }

    @Test
    @DisplayName(".submitted carries the account, window end, difference with its currency and both counts")
    void submitted() {
        when(provider.getIfAvailable()).thenReturn(writer);
        facts.submitted(recon, "preparer");
        DomainEventEnvelope<Object> envelope = published();
        assertThat(envelope.eventType()).isEqualTo("accounting.bankreconciliation.submitted");
        assertThat(envelope.actor()).isEqualTo("preparer");
        assertThat((BankReconciliationSubmittedV1) envelope.payload())
                .isEqualTo(new BankReconciliationSubmittedV1(
                        RECON_ID, ACCOUNT_ID, END, new BigDecimal("0.0100"), "USD", 0, 0, "preparer"));
    }

    @Test
    @DisplayName(".approved carries the approval snapshot, the adjusted bank balance and the period")
    void approved() {
        when(provider.getIfAvailable()).thenReturn(writer);
        facts.approved(recon, "controller");
        DomainEventEnvelope<Object> envelope = published();
        assertThat(envelope.eventType()).isEqualTo("accounting.bankreconciliation.approved");
        assertThat((BankReconciliationApprovedV1) envelope.payload())
                .isEqualTo(new BankReconciliationApprovedV1(
                        RECON_ID,
                        ACCOUNT_ID,
                        START,
                        END,
                        "2026-09",
                        new BigDecimal("1234.5600"),
                        new BigDecimal("1234.5700"),
                        "USD",
                        "controller"));
    }

    @Test
    @DisplayName(".invalidated carries the reason and the journal entry")
    void invalidated() {
        when(provider.getIfAvailable()).thenReturn(writer);
        UUID entry = UUIDv7Generator.generate();
        facts.invalidated(recon, InvalidationReason.LEDGER_LINE_REVERSED, entry, "clerk");
        DomainEventEnvelope<Object> envelope = published();
        assertThat(envelope.eventType()).isEqualTo("accounting.bankreconciliation.invalidated");
        assertThat((BankReconciliationInvalidatedV1) envelope.payload())
                .isEqualTo(new BankReconciliationInvalidatedV1(
                        RECON_ID, ACCOUNT_ID, BankReconciliationInvalidatedV1.Reason.LEDGER_LINE_REVERSED, entry));
    }

    @Test
    @DisplayName(".superseded and .cancelled name the successor and the reason")
    void supersededAndCancelled() {
        when(provider.getIfAvailable()).thenReturn(writer);
        UUID successor = UUIDv7Generator.generate();
        facts.superseded(recon, successor, "controller");
        DomainEventEnvelope<Object> superseded = published();
        assertThat(superseded.eventType()).isEqualTo("accounting.bankreconciliation.superseded");
        assertThat((BankReconciliationSupersededV1) superseded.payload())
                .isEqualTo(new BankReconciliationSupersededV1(RECON_ID, ACCOUNT_ID, successor));

        org.mockito.Mockito.clearInvocations(writer);
        facts.cancelled(recon, "Window started from the wrong statement", "controller");
        DomainEventEnvelope<Object> cancelled = published();
        assertThat(cancelled.eventType()).isEqualTo("accounting.bankreconciliation.cancelled");
        assertThat((BankReconciliationCancelledV1) cancelled.payload())
                .isEqualTo(new BankReconciliationCancelledV1(
                        RECON_ID, ACCOUNT_ID, "Window started from the wrong statement", "controller"));
    }

    @Test
    @DisplayName("nothing is queued while the Kafka rails are off (no outbox writer bean)")
    void noWriter() {
        when(provider.getIfAvailable()).thenReturn(null);
        facts.approved(recon, "controller");
        verify(writer, never()).publish(anyString(), any());
    }
}
