package com.positivity.accounting.internal.handler;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.PaymentApplicationReversalGLPostingEvent;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.JournalEntryNotReversibleException;
import com.positivity.accounting.service.IdempotencyService;
import com.positivity.accounting.service.JournalEntryService;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link PaymentApplicationReversalGLPostingEventHandler} (story
 * C2, issue #958): reversing-JE posting against the C1 cash-receipt entry so an
 * apply→reverse cycle nets to zero in the GL.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PaymentApplicationReversalGLPostingEventHandler")
class PaymentApplicationReversalGLPostingEventHandlerTest {

    private static final String APPLICATION_REQUEST_ID = "0195f1a2-0000-7000-8000-000000000042";
    private static final String IDEMPOTENCY_KEY = "PAYMENT_APPLICATION_REVERSAL_GL_POSTING:" + APPLICATION_REQUEST_ID;
    private static final UUID SOURCE_EVENT_ID = UUID.fromString(APPLICATION_REQUEST_ID);

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private JournalEntryService journalEntryService;

    @InjectMocks
    private PaymentApplicationReversalGLPostingEventHandler handler;

    private PaymentApplicationReversalGLPostingEvent testEvent;
    private UUID originalJournalEntryId;
    private UUID reversingJournalEntryId;
    private JournalEntry originalEntry;
    private JournalEntry reversingEntry;

    @BeforeEach
    void setUp() {
        originalJournalEntryId = UUID.fromString("00000000-0000-0000-0000-00000000e001");
        reversingJournalEntryId = UUID.fromString("00000000-0000-0000-0000-00000000e002");

        testEvent = PaymentApplicationReversalGLPostingEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-00000000a001"))
                .applicationRequestId(APPLICATION_REQUEST_ID)
                .reversalId(UUID.fromString("00000000-0000-0000-0000-00000000c001"))
                .paymentId(UUID.fromString("00000000-0000-0000-0000-00000000b001"))
                .reason("Customer disputed charge")
                .build();

        originalEntry = new JournalEntry();
        originalEntry.setJournalEntryId(originalJournalEntryId);
        originalEntry.setSourceEventId(SOURCE_EVENT_ID);
        originalEntry.setStatus(JournalEntryStatus.POSTED);
        // Original cash-receipt entry is not itself a reversal.
        originalEntry.setReversalJournalEntry(null);

        reversingEntry = new JournalEntry();
        reversingEntry.setJournalEntryId(reversingJournalEntryId);
    }

    @Test
    @DisplayName("happy path reverses the located cash-receipt entry and registers the idempotency key")
    void happyPath_reversesOriginalAndRegistersKey() {
        when(journalEntryService.findOriginalBySourceEvent(SOURCE_EVENT_ID)).thenReturn(Optional.of(originalEntry));
        when(journalEntryService.reverseJournalEntry(
                        eq(originalJournalEntryId), eq("Customer disputed charge"), isNull(), isNull()))
                .thenReturn(reversingEntry);

        handler.onPaymentApplicationReversalGLPosting(testEvent);

        verify(journalEntryService)
                .reverseJournalEntry(eq(originalJournalEntryId), eq("Customer disputed charge"), isNull(), isNull());
        verify(idempotencyService).registerKey(IDEMPOTENCY_KEY, reversingJournalEntryId);
    }

    @Test
    @DisplayName("duplicate work item no-ops when idempotency key already processed (no double reversing JE)")
    void duplicateWorkItem_noOps() {
        when(idempotencyService.isKeyProcessed(IDEMPOTENCY_KEY)).thenReturn(true);

        handler.onPaymentApplicationReversalGLPosting(testEvent);

        verifyNoInteractions(journalEntryService);
        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("already-REVERSED original is an idempotent no-op that records the key without re-posting")
    void alreadyReversedOriginal_idempotentNoOp() {
        originalEntry.setStatus(JournalEntryStatus.REVERSED);
        when(journalEntryService.findOriginalBySourceEvent(SOURCE_EVENT_ID)).thenReturn(Optional.of(originalEntry));

        handler.onPaymentApplicationReversalGLPosting(testEvent);

        verify(journalEntryService, never())
                .reverseJournalEntry(any(UUID.class), any(String.class), any(LocalDate.class), any(String.class));
        verify(idempotencyService).registerKey(IDEMPOTENCY_KEY, originalJournalEntryId);
    }

    @Test
    @DisplayName("missing cash-receipt entry (C1 not yet posted) fails retryable without registering the key")
    void originalNotFound_failsRetryable() {
        when(journalEntryService.findOriginalBySourceEvent(SOURCE_EVENT_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> handler.onPaymentApplicationReversalGLPosting(testEvent))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(APPLICATION_REQUEST_ID);

        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("period-closed reversal propagates unwrapped for retry and does not register the key (#944)")
    void periodClosed_propagatesForRetry() {
        when(journalEntryService.findOriginalBySourceEvent(SOURCE_EVENT_ID)).thenReturn(Optional.of(originalEntry));
        when(journalEntryService.reverseJournalEntry(eq(originalJournalEntryId), any(String.class), isNull(), isNull()))
                .thenThrow(new AccountingPeriodClosedException("2026-03", "Period 2026-03 is CLOSED"));

        assertThatThrownBy(() -> handler.onPaymentApplicationReversalGLPosting(testEvent))
                .isInstanceOf(AccountingPeriodClosedException.class);

        verify(idempotencyService, never()).registerKey(any(String.class), any(UUID.class));
    }

    @Test
    @DisplayName("lost concurrent reversal race (already REVERSED) becomes an idempotent no-op")
    void concurrentReversalRaceLost_idempotentNoOp() {
        when(journalEntryService.findOriginalBySourceEvent(SOURCE_EVENT_ID)).thenReturn(Optional.of(originalEntry));
        when(journalEntryService.reverseJournalEntry(eq(originalJournalEntryId), any(String.class), isNull(), isNull()))
                .thenThrow(new JournalEntryNotReversibleException(originalJournalEntryId, JournalEntryStatus.REVERSED));

        handler.onPaymentApplicationReversalGLPosting(testEvent);

        verify(idempotencyService).registerKey(IDEMPOTENCY_KEY, originalJournalEntryId);
    }
}
