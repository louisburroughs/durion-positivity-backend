package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.repository.InvoiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link InvoicePartyIdBackfillService} (#921): each run delegates to exactly
 * one bulk repository UPDATE, on both the patched-rows and the nothing-to-do path — the job
 * must stay a single cheap statement so re-running it forever (as the replica fills via
 * workorder event replay) costs nothing when there is no work.
 */
@ExtendWith(MockitoExtension.class)
class InvoicePartyIdBackfillServiceTest {

    @Mock
    private InvoiceRepository invoiceRepository;

    @InjectMocks
    private InvoicePartyIdBackfillService invoicePartyIdBackfillService;

    /** Rows patched: the run executes the single bulk UPDATE and completes without error. */
    @Test
    void backfill_rowsPatched_executesSingleBulkUpdate() {
        when(invoiceRepository.backfillPartyIdFromWorkorderReplica()).thenReturn(7);

        assertThatCode(() -> invoicePartyIdBackfillService.backfill()).doesNotThrowAnyException();

        verify(invoiceRepository).backfillPartyIdFromWorkorderReplica();
        verifyNoMoreInteractions(invoiceRepository);
    }

    /** Nothing to do: still exactly one statement, no extra repository traffic, no error. */
    @Test
    void backfill_nothingToPatch_isCheapNoOp() {
        when(invoiceRepository.backfillPartyIdFromWorkorderReplica()).thenReturn(0);

        assertThatCode(() -> invoicePartyIdBackfillService.backfill()).doesNotThrowAnyException();

        verify(invoiceRepository).backfillPartyIdFromWorkorderReplica();
        verifyNoMoreInteractions(invoiceRepository);
    }

    /** Idempotent by design: consecutive runs just repeat the same single statement. */
    @Test
    void backfill_repeatedRuns_repeatTheSameSingleStatement() {
        when(invoiceRepository.backfillPartyIdFromWorkorderReplica()).thenReturn(3, 0);

        invoicePartyIdBackfillService.backfill();
        invoicePartyIdBackfillService.backfill();

        verify(invoiceRepository, times(2)).backfillPartyIdFromWorkorderReplica();
        verifyNoMoreInteractions(invoiceRepository);
    }
}
