package com.positivity.invoice.internal.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.invoice.internal.repository.InvoiceRepository;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.testing.TenantTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

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

    private InvoicePartyIdBackfillService invoicePartyIdBackfillService;

    @BeforeEach
    void createService() {
        // One active tenant, the alpha default, for the per-tenant sweep (ADR-0062); the transaction
        // manager is a mock, so the TransactionTemplate just runs the body.
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        invoicePartyIdBackfillService = new InvoicePartyIdBackfillService(
                invoiceRepository,
                new TenantIterator(new StaticTenantRegistry(tenancy)),
                mock(PlatformTransactionManager.class));
    }

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
