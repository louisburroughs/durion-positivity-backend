package com.positivity.supplier.internal.invoice.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.DomainTopics;
import com.positivity.domainevents.supplier.SupplierInvoiceLine;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.entity.SupplierInvoiceEntity;
import com.positivity.supplier.internal.entity.SupplierInvoiceLineEntity;
import com.positivity.supplier.internal.repository.SupplierInvoiceRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns fetched vendor invoices into stored documents and published facts (CAP-321 #1227).
 *
 * <h2>Why the same invoice arrives more than once</h2>
 *
 * Fetch windows overlap on re-run, deliberately: a fetch that failed halfway must be safe to simply
 * repeat, and the only way to guarantee nothing was missed is to ask again for ground already
 * covered (ADR-0052 §5). So a document arriving twice is the normal case, not an error — and the
 * thing that must not happen twice is the <em>debt</em>. Identity is therefore the vendor's own:
 * profile, invoice number, issue date. A second arrival is recognised and dropped here rather than
 * published and deduplicated downstream, because a consumer that got that wrong would pay twice.
 *
 * <h2>What is not decided here</h2>
 *
 * Nothing about accounting. This module records what the vendor sent and says so; whether it posts,
 * to which accounts, and whether anyone has agreed to pay it belong to pos-accounting, and are
 * reached only through the published fact (ADR-0044 R1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceImporter {

    private static final String SOURCE = "pos-supplier";

    private final SupplierInvoiceRepository invoiceRepository;
    private final SupplierOutboxEventWriter outboxEventWriter;
    private final Clock clock;

    /**
     * Stores and publishes every invoice in a fetched batch that has not been seen before.
     *
     * @return how many were new; the rest were already held from an earlier, overlapping window
     */
    @Transactional
    public int importInvoices(
            @NonNull UUID vendorProfileId, @NonNull String supplierRef, @NonNull List<SupplierInvoice> fetched) {
        int imported = 0;
        for (SupplierInvoice invoice : fetched) {
            if (importOne(vendorProfileId, supplierRef, invoice)) {
                imported++;
            }
        }
        if (imported < fetched.size()) {
            log.debug(
                    "Vendor {}: {} of {} invoices were already held from an earlier window",
                    supplierRef,
                    fetched.size() - imported,
                    fetched.size());
        }
        return imported;
    }

    private boolean importOne(UUID vendorProfileId, String supplierRef, SupplierInvoice invoice) {
        boolean alreadyHeld = invoiceRepository
                .findByVendorProfileIdAndVendorInvoiceNumberAndInvoiceDate(
                        vendorProfileId, invoice.vendorInvoiceNumber(), invoice.invoiceDate())
                .isPresent();
        if (alreadyHeld) {
            // Deliberately not updated. A vendor that re-issues under the same identity with
            // different figures is telling us something an AP clerk needs to see, and quietly
            // overwriting the first version would destroy the evidence of the change. The consumer
            // raises that as a discrepancy against the bill it already created.
            return false;
        }

        Instant now = Instant.now(clock);
        SupplierInvoiceEntity entity = SupplierInvoiceEntity.builder()
                .vendorProfileId(vendorProfileId)
                .supplierRef(supplierRef)
                .vendorInvoiceNumber(invoice.vendorInvoiceNumber())
                .invoiceDate(invoice.invoiceDate())
                .invoiceType(invoice.type().name())
                .currency(invoice.currency())
                .totalNetAmount(invoice.totalNetAmount())
                .totalTaxAmount(invoice.totalTaxAmount())
                .totalGrossAmount(invoice.totalGrossAmount())
                .vendorOrderReference(headerOrderReference(invoice))
                .fetchedAt(now)
                .lines(new ArrayList<>())
                .build();

        for (SupplierInvoice.Line line : invoice.lines()) {
            entity.getLines()
                    .add(SupplierInvoiceLineEntity.builder()
                            .invoice(entity)
                            .lineNumber(line.lineNumber())
                            .articleEan(line.articleEan())
                            .supplierArticleCode(line.supplierArticleCode())
                            .quantity(line.quantity())
                            .unitPrice(line.unitPrice())
                            .lineAmount(line.lineAmount())
                            .vendorOrderReference(line.vendorOrderReference())
                            .build());
        }

        SupplierInvoiceEntity saved = invoiceRepository.save(entity);
        publish(saved, invoice, now);
        log.info("Imported vendor invoice {} ({}) from {}", invoice.vendorInvoiceNumber(), invoice.type(), supplierRef);
        return true;
    }

    /**
     * The document-level order reference.
     *
     * <p>Taken from the first line that carries one, because the norm puts the reference on the
     * lines and a vendor invoicing a single order repeats it on each. Where an invoice spans several
     * orders this names only the first — enough for the consumer to attempt a match, and honest
     * about the rest, which is why the per-line references travel too.
     */
    private static String headerOrderReference(SupplierInvoice invoice) {
        return invoice.lines().stream()
                .map(SupplierInvoice.Line::vendorOrderReference)
                .filter(reference -> reference != null && !reference.isBlank())
                .findFirst()
                .orElse(null);
    }

    private void publish(SupplierInvoiceEntity saved, SupplierInvoice invoice, Instant occurredAt) {
        List<SupplierInvoiceLine> lines = new ArrayList<>(invoice.lines().size());
        for (SupplierInvoice.Line line : invoice.lines()) {
            lines.add(new SupplierInvoiceLine(
                    line.lineNumber(),
                    line.articleEan(),
                    line.supplierArticleCode(),
                    line.quantity(),
                    line.unitPrice(),
                    line.lineAmount(),
                    line.vendorOrderReference()));
        }

        SupplierInvoiceReceivedV1 payload = new SupplierInvoiceReceivedV1(
                saved.getVendorProfileId(),
                saved.getSupplierRef(),
                saved.getVendorInvoiceNumber(),
                saved.getInvoiceDate(),
                SupplierInvoiceReceivedV1.Type.valueOf(saved.getInvoiceType()),
                saved.getCurrency(),
                saved.getTotalNetAmount(),
                saved.getTotalTaxAmount(),
                saved.getTotalGrossAmount(),
                saved.getVendorOrderReference(),
                occurredAt,
                lines);

        outboxEventWriter.publish(
                DomainTopics.events("supplier"),
                new DomainEventEnvelope<>(
                        UUIDv7Generator.generate(),
                        SupplierInvoiceReceivedV1.EVENT_TYPE,
                        SupplierInvoiceReceivedV1.SCHEMA_VERSION,
                        // Keyed on our own record of the document, so a redelivery of this event and
                        // a re-fetch of the same invoice are distinguishable: the first is the same
                        // event id, the second never gets published at all.
                        saved.getSupplierInvoiceId(),
                        0L,
                        occurredAt,
                        SOURCE,
                        null,
                        SOURCE,
                        payload));
    }
}
