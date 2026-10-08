package com.positivity.accounting.internal.config;

import com.positivity.domainevents.inventory.GoodsReceiptRecordedV1;
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
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Code-first registry of the accounting event types this module records (#2436). The Kafka
 * listeners derive their {@code RECORDED_EVENT_TYPES} from it, and every type the module's own code
 * submits through the API path ({@link #INVOICE_PAYMENT}, {@link #AP_PAYMENT_GL_POSTING}) is declared
 * here and referenced by its submitter, so {@code GET /v1/accounting/events/types} lists types with no
 * traffic yet and cannot drift from what the code records. {@code VENDOR_BILL_GL_POSTING} is retired
 * (CAP:550 S12, #2509; AW40): a vendor bill posts at approval through the {@code VENDOR_BILL} posting
 * category, and V17 closed the events of that type SKIPPED / {@code RETIRED_EVENT_TYPE}.
 *
 * <p>The API submit path ({@code POST /v1/accounting/events}) validates that {@code eventType} is
 * present and not {@linkplain #isRetired retired} (400 {@code VALIDATION_ERROR}; #2509 review): a
 * retired type would be posted by the engine beside the posting that replaced it. Otherwise it
 * persists any string as a {@code RECEIVED} event without checking it against this
 * registry, a posting rule set or a default GL mapping. Resolution happens afterwards, when the
 * received-event drainer processes the event: {@link #INVOICE_PAYMENT} goes to its AR subledger
 * processor, and every other type goes through the posting engine, which posts it when an active
 * posting rule set or default GL mapping resolves it and otherwise suspends it with a reason. An
 * external client can therefore record a type this registry does not list; the registry lists the
 * types the deployed code itself records, not every string a caller may submit.
 */
public final class AccountingEventTypeRegistry {

    /** Event type of a payment applied to an invoice, submitted through the REST API. */
    public static final String INVOICE_PAYMENT = "INVOICE_PAYMENT";

    /**
     * Event type of an AP payment, submitted in-process by {@code APPaymentGLPostingEventHandler} and
     * posted by the posting engine (Dr AP, Cr Cash/Bank).
     */
    public static final String AP_PAYMENT_GL_POSTING = "AP_PAYMENT_GL_POSTING";

    /**
     * Event type of a vendor bill's GL posting, retired (CAP:550 S12, #2509; AW40): the bill posts at approval
     * through the {@code VENDOR_BILL} posting category.
     */
    public static final String VENDOR_BILL_GL_POSTING = "VENDOR_BILL_GL_POSTING";

    /** Types no longer accepted: each is posted by something else now, and recording one would post twice. */
    private static final Set<String> RETIRED = Set.of(VENDOR_BILL_GL_POSTING);

    /** Source domains. */
    public static final String DOMAIN_INVOICE = "invoice";

    public static final String DOMAIN_ORDER = "order";
    public static final String DOMAIN_INVENTORY = "inventory";
    public static final String DOMAIN_SUPPLIER = "supplier";
    public static final String DOMAIN_WARRANTY = "warranty";
    public static final String DOMAIN_PAYMENT = "payment";
    public static final String DOMAIN_ACCOUNTING = "accounting";

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
                    "Register session closed (over/short and drawer movements)",
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
                    GoodsReceiptRecordedV1.EVENT_TYPE,
                    "Goods receipt recorded (accrual to goods received not yet billed)",
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
            new Entry(INVOICE_PAYMENT, "Invoice payment (AR subledger)", DOMAIN_PAYMENT, Ingestion.API, false),
            new Entry(
                    AP_PAYMENT_GL_POSTING,
                    "AP payment GL posting (accounts payable)",
                    DOMAIN_ACCOUNTING,
                    Ingestion.API,
                    true));

    private AccountingEventTypeRegistry() {}

    /** Whether {@code eventType} is retired; compared as given, trimmed and upper-cased. */
    public static boolean isRetired(@Nullable String eventType) {
        return eventType != null && RETIRED.contains(eventType.trim().toUpperCase(Locale.ROOT));
    }

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
