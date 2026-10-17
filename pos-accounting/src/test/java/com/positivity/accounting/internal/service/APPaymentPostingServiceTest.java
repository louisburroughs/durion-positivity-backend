package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.entity.APPayment;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.AccountingTimeZoneUnsetException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * An AP payment's own entry (CAP:550 S42, #2603; AW40, AW41): Dr 2000 gross / Dr 6030 fee / Cr its bank, dated its
 * {@code payment_date}, posted once, refusals unwrapped, the stored override applied as the payer.
 */
@DisplayName("AP payment posting (S42, #2603)")
class APPaymentPostingServiceTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-09T01:00:00Z"), ZoneOffset.UTC);
    private static final UUID PAYMENT_ID = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f7001");
    private static final UUID BANK = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f1000");
    private static final UUID PAYABLES = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f2000");
    private static final UUID FEES = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f6030");
    private static final UUID ENTRY = UUID.fromString("0199a1b2-c3d4-7e5f-8a9b-0c1d2e3f9001");
    private static final LocalDate PAID_ON = LocalDate.of(2026, 10, 8);

    private final APPaymentRepository payments = mock(APPaymentRepository.class);
    private final APPaymentPreGatewayChecks mappings = mock(APPaymentPreGatewayChecks.class);
    private final JournalEntryService journalEntries = mock(JournalEntryService.class);
    private final JournalEntryRepository journalEntryRows = mock(JournalEntryRepository.class);
    private final ApLockTimeout lockTimeout = mock(ApLockTimeout.class);
    private final com.positivity.accounting.internal.repository.EventOutboxRepository outbox =
            mock(com.positivity.accounting.internal.repository.EventOutboxRepository.class);
    private final AccountingPeriodGate periodGate = mock(AccountingPeriodGate.class);
    private final AccountingCalendarZoneResolver zoneResolver = mock(AccountingCalendarZoneResolver.class);

    private APPaymentPostingService service;
    private APPayment payment;

    @BeforeEach
    void wire() {
        service = new APPaymentPostingService(
                CLOCK, payments, mappings, journalEntries, journalEntryRows, lockTimeout, outbox, periodGate, zoneResolver);
        payment = new APPayment(PAYMENT_ID);
        payment.setPaymentRef("PAY-412");
        payment.setVendorName("Acme Brakes");
        payment.setGrossAmount(new BigDecimal("412.00"));
        payment.setFeeAmount(new BigDecimal("1.50"));
        payment.setCurrency("USD");
        payment.setPaymentMethod(PaymentMethod.ACH);
        payment.setBankAccountId(BANK);
        payment.setPaymentDate(PAID_ON);
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        when(payments.lockById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        lenient()
                .when(mappings.resolve(eq(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY), any()))
                .thenReturn(PAYABLES);
        lenient()
                .when(mappings.resolve(eq(APPaymentPreGatewayChecks.PAYMENT_FEES_KEY), any()))
                .thenReturn(FEES);
        lenient().when(journalEntryRows.findBySourceEvent(any())).thenReturn(List.of());
        lenient().when(journalEntryRows.getReferenceById(ENTRY)).thenReturn(new JournalEntry(ENTRY));
        lenient()
                .when(journalEntries.createJournalEntry(any()))
                .thenReturn(JournalEntryResponse.builder().journalEntryId(ENTRY).build());
        lenient()
                .when(journalEntries.postJournalEntry(eq(ENTRY), any()))
                .thenReturn(JournalEntryResponse.builder().journalEntryId(ENTRY).build());
        lenient()
                .when(journalEntries.postJournalEntryWithRecordedOverride(eq(ENTRY), anyString(), anyString()))
                .thenReturn(JournalEntryResponse.builder().journalEntryId(ENTRY).build());
    }

    private JournalEntryCreateRequest created() {
        ArgumentCaptor<JournalEntryCreateRequest> captor = ArgumentCaptor.forClass(JournalEntryCreateRequest.class);
        verify(journalEntries).createJournalEntry(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("AW41 ruling 2 (#2513): a CASH (drawer cash-on-delivery) payment posts nothing of its own")
    void cashPaymentPostsNothing() {
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        payment.setGlJournalEntry(new JournalEntry(ENTRY));

        assertThat(service.postPending(PAYMENT_ID)).isEqualTo(ENTRY);
        org.mockito.Mockito.verifyNoInteractions(journalEntries);
        org.mockito.Mockito.verify(mappings, org.mockito.Mockito.never()).resolve(any(), any());
    }

    @Test
    @DisplayName("a CASH payment without its session-close entry is a defect, not a silent no-op (#2731 review)")
    void cashPaymentWithoutItsCloseEntryIsRefused() {
        payment.setPaymentMethod(PaymentMethod.CASH);
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        payment.setGlJournalEntry(null);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.postPending(PAYMENT_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-close entry");
        org.mockito.Mockito.verifyNoInteractions(journalEntries);
    }

    @Test
    @DisplayName("AC1: 412.00 from the bank with a 1.50 fee → one entry dated payment_date: Dr 2000 412.00 / Dr 6030"
            + " 1.50 / Cr bank 413.50; the payment GL_POSTED with it")
    void entryShape() {
        assertThat(service.postPending(PAYMENT_ID)).isEqualTo(ENTRY);

        JournalEntryCreateRequest entry = created();
        assertThat(entry.getTransactionDate()).isEqualTo(PAID_ON.atStartOfDay());
        assertThat(entry.getSourceEventType()).isEqualTo(JournalEntrySourceTypes.AP_PAYMENT);
        assertThat(entry.getSourceEventId())
                .isEqualTo(UUID.nameUUIDFromBytes(("AP_PAYMENT:" + PAYMENT_ID).getBytes(StandardCharsets.UTF_8)))
                .isEqualTo(APPaymentPostingService.toSourceEventId(PAYMENT_ID));
        assertThat(entry.getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        line -> line.getDebitAmount().toPlainString(),
                        line -> line.getCreditAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PAYABLES, "412.00", "0"),
                        org.assertj.core.groups.Tuple.tuple(FEES, "1.50", "0"),
                        org.assertj.core.groups.Tuple.tuple(BANK, "0", "413.50"));
        verify(mappings).resolve(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY, PAID_ON.atStartOfDay());
        verify(journalEntries).postJournalEntry(ENTRY, null);
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(payment.getGlJournalEntryId()).isEqualTo(ENTRY);
        assertThat(payment.getGlPostedAt()).isEqualTo(Instant.now(CLOCK));
        assertThat(payment.getGlPostError()).isNull();
        verify(payments).save(payment);
    }

    @Test
    @DisplayName("a fee of 0.00 posts two lines and never needs PAYMENT_FEES")
    void zeroFee() {
        payment.setFeeAmount(new BigDecimal("0.00"));

        service.postPending(PAYMENT_ID);

        assertThat(created().getLines())
                .extracting(
                        JournalEntryCreateRequest.JournalEntryLineRequest::getGlAccountId,
                        line -> line.getDebitAmount().toPlainString(),
                        line -> line.getCreditAmount().toPlainString())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(PAYABLES, "412.00", "0"),
                        org.assertj.core.groups.Tuple.tuple(BANK, "0", "412.00"));
        verify(mappings, never()).resolve(eq(APPaymentPreGatewayChecks.PAYMENT_FEES_KEY), any());
    }

    @Test
    @DisplayName("the row is locked after the lock timeout is set, before anything is read")
    void locksThePaymentFirst() {
        service.postPending(PAYMENT_ID);

        InOrder order = inOrder(lockTimeout, payments, journalEntries);
        order.verify(lockTimeout).apply();
        order.verify(payments).lockById(PAYMENT_ID);
        order.verify(journalEntries).createJournalEntry(any());
    }

    @Test
    @DisplayName("a second delivery posts nothing: a payment already GL_POSTED, or left GL_POST_FAILED for the retry")
    void postsOnce() {
        for (APPaymentStatus status : List.of(APPaymentStatus.GL_POSTED, APPaymentStatus.GL_POST_FAILED)) {
            payment.setStatus(status);
            assertThat(service.postPending(PAYMENT_ID)).isNull();
        }
        verify(journalEntries, never()).createJournalEntry(any());
    }

    @Test
    @DisplayName("an entry already posted under AP_PAYMENT:<paymentId> is linked, never posted again")
    void durableBackstop() {
        JournalEntry posted = new JournalEntry(ENTRY);
        posted.setStatus(JournalEntryStatus.POSTED);
        when(journalEntryRows.findBySourceEvent(APPaymentPostingService.toSourceEventId(PAYMENT_ID)))
                .thenReturn(List.of(posted));

        assertThat(service.postPending(PAYMENT_ID)).isEqualTo(ENTRY);

        verify(journalEntries, never()).createJournalEntry(any());
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(payment.getGlJournalEntryId()).isEqualTo(ENTRY);
    }

    @Test
    @DisplayName("AC5: the override the payer gave on the pay command is applied as the payer")
    void recordedOverrideAppliesAsThePayer() {
        payment.setPeriodOverrideJustification("Supplier paid on the agreed date");
        payment.setPeriodOverrideBy("payer.pat");

        service.postPending(PAYMENT_ID);

        verify(journalEntries)
                .postJournalEntryWithRecordedOverride(ENTRY, "Supplier paid on the agreed date", "payer.pat");
        verify(journalEntries, never()).postJournalEntry(any(), any());
    }

    @Test
    @DisplayName("refusals propagate unwrapped and leave the payment as it was")
    void refusalsPropagateUnwrapped() {
        GLMappingNotConfiguredException missing = new GLMappingNotConfiguredException("missing");
        when(mappings.resolve(eq(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY), any()))
                .thenThrow(missing);

        assertThatThrownBy(() -> service.postPending(PAYMENT_ID)).isSameAs(missing);
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POST_PENDING);

        AccountingPeriodClosedException closed = new AccountingPeriodClosedException("2026-10", "closed");
        when(mappings.resolve(eq(APPaymentPreGatewayChecks.ACCOUNTS_PAYABLE_KEY), any()))
                .thenReturn(PAYABLES);
        when(journalEntries.postJournalEntry(ENTRY, null)).thenThrow(closed);
        assertThatThrownBy(() -> service.postPending(PAYMENT_ID)).isSameAs(closed);
    }

    @Test
    @DisplayName("refusalCode: the four refusals are not transient; anything else is")
    void refusalCodes() {
        assertThat(APPaymentPostingService.refusalCode(new GLMappingNotConfiguredException("x")))
                .contains("GL_MAPPING_NOT_CONFIGURED");
        assertThat(APPaymentPostingService.refusalCode(new AccountingPeriodClosedException("2026-10", "x")))
                .contains("PERIOD_CLOSED");
        assertThat(APPaymentPostingService.refusalCode(new AccountingPeriodHardLockedException(PAID_ON, "x")))
                .contains("PERIOD_HARD_LOCKED");
        assertThat(APPaymentPostingService.refusalCode(new AccountingTimeZoneUnsetException()))
                .contains("ACCOUNTING_TIME_ZONE_UNSET");
        assertThat(APPaymentPostingService.refusalCode(new IllegalStateException("db down")))
                .isEmpty();
        assertThat(APPaymentPostingService.refusalCode(
                        new VendorBillException(VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE, "x")))
                .isEmpty();
    }

    @Test
    @DisplayName("retry: only a GL_POST_FAILED payment, on its stored date; anything else is AP_PAYMENT_NOT_RETRYABLE")
    void retryOnlyAFailedPosting() {
        for (APPaymentStatus status : List.of(
                APPaymentStatus.GL_POSTED,
                APPaymentStatus.GL_POST_PENDING,
                APPaymentStatus.GATEWAY_PENDING,
                APPaymentStatus.GATEWAY_FAILED)) {
            payment.setStatus(status);
            assertThatThrownBy(() -> service.retry(PAYMENT_ID, null))
                    .as("status %s", status)
                    .isInstanceOfSatisfying(
                            VendorBillException.class,
                            refusal -> assertThat(refusal.getCode())
                                    .isEqualTo(VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE));
        }
        verify(journalEntries, never()).createJournalEntry(any());

        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setGlPostError("GL_MAPPING_NOT_CONFIGURED");
        assertThat(service.retry(PAYMENT_ID, null)).isEqualTo(ENTRY);
        assertThat(created().getTransactionDate()).isEqualTo(PAID_ON.atStartOfDay());
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(payment.getGlPostError()).isNull();
    }

    @Test
    @DisplayName("#2729: a GL_POST_PENDING payment whose outbox row is FAILED (and none pending) is retryable")
    void retryPendingWithFailedOutbox() {
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        when(payments.lockById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(outbox.countByAggregateIdAndStatus(PAYMENT_ID, com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus.FAILED))
                .thenReturn(1L);
        when(outbox.countByAggregateIdAndStatus(PAYMENT_ID, com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus.PENDING))
                .thenReturn(0L);

        assertThat(service.retry(PAYMENT_ID, null, false)).isEqualTo(ENTRY);
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
    }

    @Test
    @DisplayName("#2729: a GL_POST_PENDING payment with a live outbox row is not retryable, and says to wait")
    void retryPendingWithLiveOutboxIsRefused() {
        payment.setStatus(APPaymentStatus.GL_POST_PENDING);
        when(payments.lockById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(outbox.countByAggregateIdAndStatus(PAYMENT_ID, com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus.FAILED))
                .thenReturn(0L);

        assertThatThrownBy(() -> service.retry(PAYMENT_ID, null, false))
                .isInstanceOfSatisfying(VendorBillException.class, e -> assertThat(e.getCode())
                        .isEqualTo(VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE))
                .hasMessageContaining("wait for it");
    }

    @Test
    @DisplayName("#2729 (AW42): deferPastHardLock dates the entry on today's business date when payment_date is"
            + " hard-locked, names the original date, and records DEFERRED_PAST_HARD_LOCK; without the lock it is a"
            + " plain retry on the payment's own date")
    void deferPastHardLock() {
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        when(payments.lockById(PAYMENT_ID)).thenReturn(Optional.of(payment));
        when(periodGate.isHardLocked(PAID_ON)).thenReturn(true);
        java.time.LocalDate today = PAID_ON.plusMonths(2);
        when(zoneResolver.today()).thenReturn(today);

        assertThat(service.retry(PAYMENT_ID, null, true)).isEqualTo(ENTRY);
        JournalEntryCreateRequest entry = created();
        assertThat(entry.getTransactionDate()).isEqualTo(today.atStartOfDay());
        assertThat(entry.getDescription()).contains("paid " + PAID_ON).contains("after the hard lock");
        assertThat(payment.getPostingDateRule()).isEqualTo(APPaymentPostingService.DEFERRED_PAST_HARD_LOCK);
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);

        // Not hard-locked: the flag changes nothing.
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setPostingDateRule(null);
        when(periodGate.isHardLocked(PAID_ON)).thenReturn(false);
        service.retry(PAYMENT_ID, null, true);
        assertThat(payment.getPostingDateRule()).isNull();
    }

    @Test
    @DisplayName("retry: an unknown payment is 404 NOT_FOUND")
    void retryUnknownPayment() {
        UUID unknown = UUID.randomUUID();
        when(payments.lockById(unknown)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.retry(unknown, null)).isInstanceOf(EntityNotFoundException.class);
    }

    @Test
    @DisplayName("retry: the caller's own override is applied for the caller; without one the stored override is")
    void retryOverride() {
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setPeriodOverrideJustification("Supplier paid on the agreed date");
        payment.setPeriodOverrideBy("payer.pat");

        service.retry(PAYMENT_ID, "Reopened June for the audit adjustments");

        verify(journalEntries).postJournalEntry(ENTRY, "Reopened June for the audit adjustments");
        verify(journalEntries, never()).postJournalEntryWithRecordedOverride(any(), any(), any());

        // Ruling 1 (2026-10-08): a retry with no override of its own never reuses the payer's stored override.
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        service.retry(PAYMENT_ID, null);
        verify(journalEntries).postJournalEntry(ENTRY, null);
        verify(journalEntries, never()).postJournalEntryWithRecordedOverride(any(), any(), any());
    }

    @Test
    @DisplayName("ruling 1: a retry with no override into a still-closed period is refused PERIOD_CLOSED; the payment"
            + " stays as it was and its stored override fields are unchanged")
    void retryIntoAClosedPeriodWithoutItsOwnOverride() {
        payment.setStatus(APPaymentStatus.GL_POST_FAILED);
        payment.setGlPostError("GL_MAPPING_NOT_CONFIGURED");
        payment.setPeriodOverrideJustification("Supplier paid on the agreed date");
        payment.setPeriodOverrideBy("payer.pat");
        AccountingPeriodClosedException closed = new AccountingPeriodClosedException("2026-10", "closed");
        when(journalEntries.postJournalEntry(ENTRY, null)).thenThrow(closed);

        assertThatThrownBy(() -> service.retry(PAYMENT_ID, null)).isSameAs(closed);

        assertThat(APPaymentPostingService.refusalCode(closed)).contains("PERIOD_CLOSED");
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POST_FAILED);
        assertThat(payment.getPeriodOverrideJustification()).isEqualTo("Supplier paid on the agreed date");
        assertThat(payment.getPeriodOverrideBy()).isEqualTo("payer.pat");
        verify(journalEntries, never()).postJournalEntryWithRecordedOverride(any(), any(), any());
    }

    @Test
    @DisplayName("the outbox delivery, and only it, applies the stored override as the payer")
    void outboxAppliesTheStoredOverride() {
        payment.setPeriodOverrideJustification("Supplier paid on the agreed date");
        payment.setPeriodOverrideBy("payer.pat");

        service.postPending(PAYMENT_ID);

        verify(journalEntries)
                .postJournalEntryWithRecordedOverride(ENTRY, "Supplier paid on the agreed date", "payer.pat");
        verify(journalEntries, never()).postJournalEntry(any(), any());
    }

    @Test
    @DisplayName("MAJOR 2: an inactive bank account at posting is a refusal (GL_ACCOUNT_NOT_ACTIVE), not transient")
    void inactiveAccountIsARefusal() {
        assertThat(APPaymentPostingService.refusalCode(
                        new com.positivity.accounting.internal.exception.GLAccountNotActiveException("inactive")))
                .contains("GL_ACCOUNT_NOT_ACTIVE");
    }
}
