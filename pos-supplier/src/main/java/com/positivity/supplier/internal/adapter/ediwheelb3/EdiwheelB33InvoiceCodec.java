package com.positivity.supplier.internal.adapter.ediwheelb3;

import com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlSupport;
import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierInvoice;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import jakarta.xml.bind.JAXBContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EDIWheel Invoice B3.3: the vendor's AR documents for a date window (CAP-321 #1227).
 *
 * <h2>Amounts are transcribed, never recalculated</h2>
 *
 * Every monetary figure is taken as the vendor stated it. Totals are not derived from the lines and
 * lines are not reconciled against totals, even where they disagree — a vendor invoice is a claim
 * about money owed, and an integration that quietly corrects it produces a number nobody can trace
 * to a document. A disagreement is something for a person to see, not for this codec to resolve.
 *
 * <h2>Document type decides the sign of the claim</h2>
 *
 * The norm distinguishes four AR documents by code, and getting the mapping wrong inverts a debt:
 * {@code 380} commercial invoice and {@code 84} debit adjustment are money owed to the vendor;
 * {@code 381} credit note and {@code 83} credit adjustment are money owed back. A code this build
 * does not recognise is rejected rather than defaulted, because guessing the direction of a payment
 * is worse than not importing it.
 */
@Component
public class EdiwheelB33InvoiceCodec implements SupplierAdapterCodec {

    /** The window bounds the norm expects: {@code yyyy-MM-dd}. */
    private static final DateTimeFormatter WINDOW_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Document type codes that mean money owed to the vendor. */
    private static final Set<String> INVOICE_CODES = Set.of("380", "84");

    /** Document type codes that mean money owed back by the vendor. */
    private static final Set<String> CREDIT_NOTE_CODES = Set.of("381", "83");

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EdiwheelB33InvoiceCodec.class);

    private static final JAXBContext CONTEXT = EdiwheelXmlSupport.contextFor(InvoiceB33Wire.InvoiceList.class);

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.INVOICE_FETCH;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_B;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.B3_3;
    }

    /**
     * Builds the windowed fetch.
     *
     * <p>Marked idempotent: asking again for the same window is a read of vendor state with no
     * commercial effect, which is what lets the fetch retry freely and lets windows overlap on
     * re-run (ADR-0052 §5). Deduplication happens downstream, on the vendor's own invoice identity.
     */
    @NonNull
    public SupplierRequestSpec buildRequest(
            @NonNull PartyContext partyContext, @NonNull LocalDate fromDate, @NonNull LocalDate toDate) {
        if (toDate.isBefore(fromDate)) {
            throw new InvoiceDecodeException("Invoice window ends before it begins: " + fromDate + " to " + toDate);
        }
        Map<String, String> query = new LinkedHashMap<>();
        query.put("invoiceIssueFromDate", WINDOW_DATE.format(fromDate));
        query.put("invoiceIssueToDate", WINDOW_DATE.format(toDate));
        query.put("buyerParty", partyContext.billingAccountNumber());
        if (partyContext.billingAgencyCode() != null
                && !partyContext.billingAgencyCode().isBlank()) {
            query.put("agencyCode", partyContext.billingAgencyCode());
        }
        return new SupplierRequestSpec("GET", null, query, null, null, "application/xml", true);
    }

    /**
     * Decodes a B3.3 response into canonical invoices.
     *
     * @throws InvoiceDecodeException when the body is not a B3.3 document, or the vendor signalled a
     *     document-level error
     */
    @NonNull
    public List<SupplierInvoice> decode(@Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new InvoiceDecodeException("Invoice response body was empty");
        }

        InvoiceB33Wire.InvoiceList document;
        try {
            document = EdiwheelXmlSupport.unmarshal(CONTEXT, InvoiceB33Wire.InvoiceList.class, body);
        } catch (Exception e) {
            throw new InvoiceDecodeException("Invoice response was not a readable B3.3 document", e);
        }

        if (document.errorHead != null && document.errorHead.errorCode != null) {
            throw new InvoiceDecodeException("Vendor refused the invoice window: " + document.errorHead.errorCode
                    + (document.errorHead.errorText == null ? "" : " — " + document.errorHead.errorText));
        }
        if (document.invoices == null || document.invoices.isEmpty()) {
            // A window with no invoices is an ordinary answer, not a failure: most days a vendor
            // issues nothing.
            return List.of();
        }

        List<SupplierInvoice> invoices = new ArrayList<>(document.invoices.size());
        for (InvoiceB33Wire.Invoice wire : document.invoices) {
            invoices.add(toCanonical(wire));
        }
        return invoices;
    }

    private static SupplierInvoice toCanonical(InvoiceB33Wire.Invoice wire) {
        String number = trimToNull(wire.documentNumber);
        if (number == null) {
            // The invoice number is half the identity everything downstream deduplicates on.
            // Without it the same document would be imported afresh on every overlapping window.
            throw new InvoiceDecodeException("Vendor invoice carries no DocumentNumber");
        }

        List<SupplierInvoice.Line> lines = new ArrayList<>();
        if (wire.lineLevel != null) {
            int position = 0;
            for (InvoiceB33Wire.Line line : wire.lineLevel) {
                lines.add(toCanonicalLine(line, ++position));
            }
        }

        return new SupplierInvoice(
                number,
                typeOf(wire.documentTypeCode, number),
                issueDateOf(wire.issueDate, number),
                currencyOf(wire.documentCurrency),
                amount(wire.summary == null ? null : wire.summary.taxableAmount),
                amount(wire.summary == null ? null : wire.summary.taxAmount),
                // The vendor's own total, not the sum of the lines. Where they disagree the vendor's
                // figure is what it will expect to be paid, and the disagreement is worth seeing.
                amount(wire.summary == null ? null : wire.summary.totalAmount),
                lines);
    }

    /**
     * @param position 1-based position in the document, used when the vendor's own line id is
     *     absent or not a number. The canonical line requires a number, and an invoice that arrives
     *     with unusable line ids is still an invoice worth importing — the totals are what the AP
     *     side pays on, and the line's place in the document is enough to refer to it by.
     */
    private static SupplierInvoice.Line toCanonicalLine(InvoiceB33Wire.Line wire, int position) {
        InvoiceB33Wire.ArticleIdentification identification =
                wire.article == null ? null : wire.article.articleIdentification;
        return new SupplierInvoice.Line(
                lineNumberOf(wire.lineId, position),
                identification == null ? null : trimToNull(identification.eanuccArticleId),
                identification == null ? null : trimToNull(identification.manufacturersArticleId),
                quantity(wire.article),
                wire.article == null || wire.article.priceDetails == null
                        ? null
                        : decimal(wire.article.priceDetails.priceAmount),
                amount(wire.lineItemNetAmount),
                orderReferenceOf(wire.references));
    }

    /**
     * Which way the money runs.
     *
     * <p>Rejected rather than defaulted when unrecognised: an unknown code could be either
     * direction, and importing a credit as a debt is a mistake that reaches a payment run.
     */
    private static SupplierInvoice.Type typeOf(@Nullable String documentTypeCode, String number) {
        String code = trimToNull(documentTypeCode);
        if (code == null) {
            throw new InvoiceDecodeException("Vendor invoice " + number + " carries no DocumentTypeCode");
        }
        if (INVOICE_CODES.contains(code)) {
            return SupplierInvoice.Type.INVOICE;
        }
        if (CREDIT_NOTE_CODES.contains(code)) {
            return SupplierInvoice.Type.CREDIT_NOTE;
        }
        throw new InvoiceDecodeException("Vendor invoice " + number + " carries an unrecognised DocumentTypeCode '"
                + code + "'; refusing to guess whether it is owed to or by the vendor");
    }

    private static LocalDate issueDateOf(@Nullable String raw, String number) {
        String value = trimToNull(raw);
        if (value == null) {
            throw new InvoiceDecodeException("Vendor invoice " + number + " carries no IssueDate");
        }
        try {
            return LocalDate.parse(value, WINDOW_DATE);
        } catch (DateTimeParseException e) {
            throw new InvoiceDecodeException(
                    "Vendor invoice " + number + " carries an unreadable IssueDate '" + value + "'", e);
        }
    }

    private static String currencyOf(InvoiceB33Wire.@Nullable DocumentCurrency currency) {
        String code = currency == null ? null : trimToNull(currency.currencyCode);
        if (code == null) {
            // An amount with no currency is not a sum of money. Defaulting to the profile's currency
            // would let a foreign-currency invoice be paid as though it were domestic.
            throw new InvoiceDecodeException("Vendor invoice carries no DocumentCurrency");
        }
        return code;
    }

    private static int lineNumberOf(@Nullable String rawLineId, int position) {
        String value = trimToNull(rawLineId);
        if (value == null) {
            return position;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed >= 1 ? parsed : position;
        } catch (NumberFormatException e) {
            return position;
        }
    }

    private static @Nullable Integer quantity(InvoiceB33Wire.@Nullable Article article) {
        if (article == null || article.invoicedQuantity == null) {
            return null;
        }
        BigDecimal value = decimal(article.invoicedQuantity.quantityValue);
        if (value == null) {
            return null;
        }
        try {
            // Exact, so "12.000" is twelve and "1.9" is not one. Truncating would quietly restate
            // what the vendor invoiced, and the line would then disagree with its own amount.
            return value.intValueExact();
        } catch (ArithmeticException e) {
            log.warn("Vendor invoice line carries a non-whole quantity '{}'; recorded as absent", value);
            return null;
        }
    }

    private static @Nullable String orderReferenceOf(InvoiceB33Wire.@Nullable HeaderReferences references) {
        if (references == null || references.orderReference == null) {
            return null;
        }
        return trimToNull(references.orderReference.referenceId);
    }

    private static @Nullable BigDecimal amount(InvoiceB33Wire.@Nullable AmountType amount) {
        return amount == null ? null : decimal(amount.amountValue);
    }

    /**
     * An amount as stated, or null when it cannot be read.
     *
     * <p>Null rather than zero. Zero is a figure the vendor might genuinely have sent, and treating
     * an unreadable amount as one would make a broken invoice indistinguishable from a nil invoice.
     */
    private static @Nullable BigDecimal decimal(@Nullable String raw) {
        String value = trimToNull(raw);
        if (value == null) {
            return null;
        }
        try {
            // Comma as the decimal separator, which the EDIWheel norms use and which every sibling
            // codec already normalises. Without this a perfectly good "240,00" would be recorded as
            // an unreadable amount, and the invoice would arrive with no total at all.
            return new BigDecimal(value.replace(',', '.'));
        } catch (NumberFormatException e) {
            log.warn("Vendor invoice carries an unreadable amount '{}'; recorded as absent", value);
            return null;
        }
    }

    private static @Nullable String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
