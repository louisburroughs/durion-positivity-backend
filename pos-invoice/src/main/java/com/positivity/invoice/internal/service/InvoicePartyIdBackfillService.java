package com.positivity.invoice.internal.service;

import com.positivity.invoice.internal.repository.InvoiceRepository;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patches {@code invoices.customer_id} for pre-#920 invoices from the event-fed
 * {@code ext_workorder} replica (#921).
 *
 * <p>Invoices created before #920 have a NULL party and therefore never match the pos-warranty
 * candidate-line search. The owning customer arrives on {@code workorder.workorder.updated}
 * facts ({@link WorkorderEventsListener}) and lands on the replica; each run here executes one
 * bulk set-based UPDATE copying it onto every still-NULL invoice whose replica row carries a
 * customer. Re-running forever is intentional: the replica fills over time via workorder event
 * replay, so rows become patchable long after this service first ran. The UPDATE is idempotent
 * (a patched row no longer matches {@code customer_id IS NULL}) and cheap when nothing is left
 * to do.
 *
 * <p>Fixed-delay scheduling runs the first pass immediately at startup and every
 * {@code pos.invoice.party-backfill.interval-ms} (default 1 h) thereafter; disable entirely
 * with {@code pos.invoice.party-backfill.enabled=false} (default enabled).
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.invoice.party-backfill",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InvoicePartyIdBackfillService {

    private final InvoiceRepository invoiceRepository;

    public InvoicePartyIdBackfillService(@NonNull InvoiceRepository invoiceRepository) {
        this.invoiceRepository = invoiceRepository;
    }

    /** One backfill pass: a single bulk UPDATE; logs the patched-row count only when > 0. */
    @Scheduled(fixedDelayString = "${pos.invoice.party-backfill.interval-ms:3600000}")
    @Transactional
    public void backfill() {
        int patched = invoiceRepository.backfillPartyIdFromWorkorderReplica();
        if (patched > 0) {
            log.info("Party-id backfill patched {} invoice(s) from ext_workorder (#921)", patched);
        } else {
            log.debug("Party-id backfill found nothing to patch");
        }
    }
}
