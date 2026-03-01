package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.dto.InvoiceFinalizedEvent;
import com.positivity.invoice.internal.entity.Invoice;
import com.positivity.invoice.internal.enums.InvoiceStatus;
import com.positivity.invoice.internal.repository.InvoiceRepository;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * CAP-248 AC5: GL posting handler — idempotent by invoiceId, retry logic TBD.
 *
 * <p>
 * Listens for {@link InvoiceFinalizedEvent} and simulates asynchronous
 * accounting (GL) posting. On success the invoice is transitioned to
 * {@code POSTED} and the {@code glEntryId} is recorded. On failure the invoice
 * is marked {@code ERROR} for retry.
 */
@Component
public class InvoiceFinalizedEventHandler {

    private static final Logger log = LoggerFactory.getLogger(InvoiceFinalizedEventHandler.class);

    private final InvoiceRepository invoiceRepository;

    public InvoiceFinalizedEventHandler(@NonNull InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /**
     * Handles {@link InvoiceFinalizedEvent} asynchronously.
     *
     * <p>
     * Simulates GL entry creation and updates the invoice status to
     * {@code POSTED} or {@code ERROR}. Idempotent: if the invoice is already
     * {@code POSTED} the event is silently skipped.
     *
     * @param event the finalized-invoice event emitted by
     *              {@code InvoiceFinalizationServiceImpl}
     */
    @Async
    @EventListener
    public void onInvoiceFinalized(@NonNull InvoiceFinalizedEvent event) {
        UUID invoiceId = event.invoiceId();
        log.info("GL posting handler invoked: invoiceId={}, workorderId={}, amount={}",
                invoiceId, event.workorderId(), event.grandTotal());

        invoiceRepository.findById(invoiceId).ifPresentOrElse(
                invoice -> postToGl(invoice, event),
                () -> log.warn("GL posting skipped — invoice not found: invoiceId={}", invoiceId));
    }

    private void postToGl(@NonNull Invoice invoice, @NonNull InvoiceFinalizedEvent event) {
        // Idempotency guard: skip if already POSTED
        if (invoice.getStatus() == InvoiceStatus.POSTED) {
            log.info("GL posting skipped — invoice already POSTED: invoiceId={}", invoice.getId());
            return;
        }

        try {
            // Simulate GL entry creation; production implementation connects to
            // pos-accounting via event bus (CAP-248 AC5 — retry infrastructure TBD)
            UUID glEntryId = UUID.randomUUID();

            invoice.setStatus(InvoiceStatus.POSTED);
            invoice.setGlEntryId(glEntryId);
            invoiceRepository.save(invoice);

            log.info("GL posting succeeded: invoiceId={}, glEntryId={}, amount={}",
                    invoice.getId(), glEntryId, event.grandTotal());
        } catch (Exception e) {
            log.error("GL posting failed: invoiceId={}, error={}", invoice.getId(), e.getMessage(), e);

            invoice.setStatus(InvoiceStatus.ERROR);
            invoiceRepository.save(invoice);
        }
    }
}
