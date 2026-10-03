package com.positivity.accounting.internal.config;

import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
import java.util.List;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;

/**
 * Code-first registry of the accounting event types this module records (#2436). The Kafka
 * listeners derive their {@code RECORDED_EVENT_TYPES} from it and the submit path references
 * {@link #INVOICE_PAYMENT}, so {@code GET /v1/accounting/events/types} lists types with no traffic
 * yet and cannot drift from what is actually recorded.
 *
 * <p>The API submit path ({@code POST /v1/accounting/events}) accepts any event type a published
 * posting rule or default GL mapping resolves; only {@link #INVOICE_PAYMENT} has a dedicated
 * processor and is listed as an {@link Ingestion#API} type.
 */
public final class AccountingEventTypeRegistry {

    /** Event type of a payment applied to an invoice, submitted through the REST API. */
    public static final String INVOICE_PAYMENT = "INVOICE_PAYMENT";

    /** Source domains. */
    public static final String DOMAIN_INVOICE = "invoice";

    public static final String DOMAIN_ORDER = "order";
    public static final String DOMAIN_INVENTORY = "inventory";
    public static final String DOMAIN_SUPPLIER = "supplier";
    public static final String DOMAIN_WARRANTY = "warranty";
    public static final String DOMAIN_PAYMENT = "payment";

    /** How an event type reaches accounting. */
    public enum Ingestion {
        KAFKA,
        API
    }

    /** One registered accounting event type. */
    public record Entry(
            @NonNull String code,
            @NonNull String displayName,
            @NonNull String sourceDomain,
            @NonNull Ingestion ingestion,
            boolean postsToGl) {}

    private static final List<Entry> ENTRIES = List.of(
            new Entry(
                    InvoiceUpdatedV1.EVENT_TYPE,
                    "Invoice updated (revenue recognition)",
                    DOMAIN_INVOICE,
                    Ingestion.KAFKA,
                    true),
            new Entry(
                    RegisterSessionClosedV1.EVENT_TYPE,
                    "Register session closed (over/short)",
                    DOMAIN_ORDER,
                    Ingestion.KAFKA,
                    true),
            new Entry(ScrapPostedV1.EVENT_TYPE, "Inventory scrap posted", DOMAIN_INVENTORY, Ingestion.KAFKA, true),
            new Entry(
                    InventoryAdjustedV1.EVENT_TYPE,
                    "Inventory adjustment posted",
                    DOMAIN_INVENTORY,
                    Ingestion.KAFKA,
                    true),
            new Entry(
                    ProductValueChangedV1.EVENT_TYPE,
                    "Inventory product value changed (revaluation)",
                    DOMAIN_INVENTORY,
                    Ingestion.KAFKA,
                    true),
            new Entry(
                    SupplierInvoiceReceivedV1.EVENT_TYPE,
                    "Supplier invoice received (vendor bill)",
                    DOMAIN_SUPPLIER,
                    Ingestion.KAFKA,
                    false),
            new Entry(
                    WarrantyReimbursementSubmittedV1.EVENT_TYPE,
                    "Warranty reimbursement submitted",
                    DOMAIN_WARRANTY,
                    Ingestion.KAFKA,
                    false),
            new Entry(
                    WarrantyReimbursementResolvedV1.EVENT_TYPE,
                    "Warranty reimbursement resolved",
                    DOMAIN_WARRANTY,
                    Ingestion.KAFKA,
                    false),
            new Entry(
                    PaymentSettledV1.EVENT_TYPE,
                    "Payment settled (held for currency)",
                    DOMAIN_PAYMENT,
                    Ingestion.KAFKA,
                    false),
            new Entry(INVOICE_PAYMENT, "Invoice payment (AR subledger)", DOMAIN_PAYMENT, Ingestion.API, false));

    private AccountingEventTypeRegistry() {}

    /** Every registered event type. */
    public static @NonNull List<Entry> entries() {
        return ENTRIES;
    }

    /** Codes of the Kafka-ingested types of one source domain. */
    public static @NonNull List<String> kafkaCodes(@NonNull String sourceDomain) {
        return ENTRIES.stream()
                .filter(e ->
                        e.ingestion() == Ingestion.KAFKA && e.sourceDomain().equals(sourceDomain))
                .map(Entry::code)
                .collect(Collectors.toUnmodifiableList());
    }
}
