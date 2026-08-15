package com.positivity.supplier.internal.adapter.ediwheela2;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import jakarta.xml.bind.JAXBContext;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EDIWheel A2.5 stock inquiry codec: canonical inquiry in, canonical availability out
 * ({@code durion/docs/ediwheel/Swagger_UPX-Stock Inquiry PROD_1_2.yaml}, {@code POST /A2_5/inquiry}).
 *
 * <h2>What this norm answers</h2>
 *
 * A four-valued {@code Availability} code per line, plus a schedule of quantities against delivery
 * dates. It carries <strong>no price</strong>; the sibling C1.0 inquiry is the norm that adds
 * {@code PriceDetails}. The canonical quote fields therefore stay null here rather than being filled
 * with a number the vendor never stated.
 *
 * <h2>Three ways to have no quantity, and they are not the same</h2>
 *
 * <ul>
 *   <li>{@code Availability=3} — the vendor says it has none. A stated zero, and the only case where
 *       this codec emits {@code 0}.
 *   <li>{@code Availability=0} — the vendor could not determine availability. Null: silence.
 *   <li>A line error naming the article as unknown — the vendor has never heard of it. Null, and
 *       {@code NOT_LISTED}, because retrying an identical inquiry cannot change the answer.
 * </ul>
 *
 * Coercing any of these into the others would put a definite sentence in front of a customer on the
 * strength of a vendor's silence.
 *
 * <h2>Why some vendor errors are our fault, not theirs</h2>
 *
 * The EDIWheel error-code table ({@code EDIWheel-XML_Order_Response_C1.pdf}) distinguishes vendor
 * trouble from a buyer whose profile is wrong: an unmapped consignee, an invalid buyer id, a wrong
 * agency-code qualifier. Reporting those as {@code SUPPLIER_UNAVAILABLE} would tell an operator to
 * wait for a vendor that is working perfectly. They map to {@code CONFIGURATION_ERROR} so the
 * operator is pointed at the profile instead.
 */
@Component
public class EdiwheelA25StockInquiryCodec implements SupplierAdapterCodec {

    /** Fixed by the norm: this document family is {@code A2}. */
    private static final String DOCUMENT_ID = "A2";

    /** Fixed by the norm: variant {@code 5} is A2.5. */
    private static final String VARIANT = "5";

    /** Error codes that are not errors, per the spec's own enums and code table. */
    private static final Set<String> NON_ERROR_CODES = Set.of("0", "1");

    /**
     * Line-level codes meaning "this article is not one I can sell you", as opposed to a transient
     * failure. Retrying an identical inquiry against these cannot succeed.
     *
     * <ul>
     *   <li>{@code 903} invalid article identification code
     *   <li>{@code 911} incorrect EAN number (not known on supplier side)
     *   <li>{@code 928} mandatory EAN code missing for this line
     *   <li>{@code 933} this material cannot be ordered via ad hoc EDI
     *   <li>{@code 940} incorrect supplier material number (not known on supplier side)
     *   <li>{@code 941} wrong EAN / supplier material number combination
     *   <li>{@code 945} material status set to not sellable
     * </ul>
     */
    private static final Set<String> NOT_LISTED_LINE_CODES = Set.of("903", "911", "928", "933", "940", "941", "945");

    /**
     * Header-level codes describing <em>our</em> configuration rather than the vendor's health.
     *
     * <ul>
     *   <li>{@code 907}, {@code 913} invalid consignee (ship-to) party id
     *   <li>{@code 908} no authority to select consignee
     *   <li>{@code 914} missing buyer (sold-to) identification number
     *   <li>{@code 915} buyer party id invalid
     *   <li>{@code 920} account not activated for this buyer party id
     *   <li>{@code 924}, {@code 925} incorrect buyer / consignee agency code qualifier
     *   <li>{@code 926}, {@code 927} missing mapping for the customer-assigned buyer / consignee id
     *   <li>{@code 930} buyer definition error in the supplier system
     *   <li>{@code 936} wrong buyer-consignee combination
     *   <li>{@code 937} consignee definition error in the supplier system
     * </ul>
     */
    private static final Set<String> CONFIGURATION_HEADER_CODES =
            Set.of("907", "908", "913", "914", "915", "920", "924", "925", "926", "927", "930", "936", "937");

    private final JAXBContext inquiryContext = A2XmlSupport.contextFor(StockInquiryA2Wire.Inquiry.class);
    private final JAXBContext quoteContext = A2XmlSupport.contextFor(StockInquiryA2Wire.Quote.class);

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.STOCK_INQUIRY;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_A25;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.A2_5;
    }

    /**
     * Builds the transport-free request for one inquiry.
     *
     * @param inquiry the canonical inquiry
     * @param partyContext buyer identity
     * @param deliveryAccountNumber the vendor's account number for the receiving location
     * @param deliveryAgencyCode agency code of that delivery account
     * @throws StockInquiryEncodeException when a required vendor field has no canonical source
     */
    @NonNull
    public SupplierRequestSpec buildRequest(
            @NonNull SupplierStockInquiry inquiry,
            @NonNull PartyContext partyContext,
            @Nullable String deliveryAccountNumber,
            @Nullable String deliveryAgencyCode) {
        String body = encodeInquiry(inquiry, partyContext, deliveryAccountNumber, deliveryAgencyCode);
        // Idempotent: an inquiry commits nothing, so a pre-send failure may be retried freely
        // (ADR-0052 §5) — unlike an order, where a retry can duplicate a physical delivery.
        return new SupplierRequestSpec("POST", null, Map.of(), body, "application/xml", "application/xml", true);
    }

    /**
     * Serialises a canonical inquiry as an A2.5 {@code inquiry_A2} document.
     *
     * @throws StockInquiryEncodeException when a required vendor field has no canonical source
     */
    @NonNull
    public String encodeInquiry(
            @NonNull SupplierStockInquiry inquiry,
            @NonNull PartyContext partyContext,
            @Nullable String deliveryAccountNumber,
            @Nullable String deliveryAgencyCode) {
        StockInquiryA2Wire.Inquiry document = new StockInquiryA2Wire.Inquiry();
        document.documentId = DOCUMENT_ID;
        document.variant = VARIANT;
        document.customerReference =
                StockInquiryA2Wire.DocumentReference.of(inquiry.inquiryId().toString());
        document.buyerParty = StockInquiryA2Wire.Party.of(
                partyContext.billingAccountNumber(),
                requireAgencyCode(partyContext.billingAgencyCode(), "billing account"));

        // Availability is consignee-specific. A profile that cannot name the receiving location's
        // account produces an inquiry the vendor would answer for the wrong place, so the encode
        // fails here rather than asking a question whose answer would be quietly misleading.
        if (deliveryAccountNumber == null || deliveryAccountNumber.isBlank()) {
            throw new StockInquiryEncodeException(
                    "Stock inquiry requires a delivery account for the receiving location, but the vendor profile"
                            + " maps none");
        }
        document.consignee = StockInquiryA2Wire.Party.of(
                deliveryAccountNumber, requireAgencyCode(deliveryAgencyCode, "delivery account"));

        int lineNumber = 0;
        for (SupplierStockInquiry.Line line : inquiry.lines()) {
            lineNumber++;
            document.orderLines.add(toWireLine(line, lineNumber));
        }
        return A2XmlSupport.marshal(inquiryContext, document);
    }

    /**
     * Decodes an A2.5 {@code quote_A2} into the canonical result.
     *
     * @param body response body as received
     * @throws StockInquiryDecodeException when the body is not a readable A2.5 quote
     */
    @NonNull
    public SupplierStockInquiryResult decodeQuote(@Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new StockInquiryDecodeException("Stock inquiry response body was empty");
        }
        StockInquiryA2Wire.Quote quote = A2XmlSupport.unmarshal(quoteContext, StockInquiryA2Wire.Quote.class, body);

        String headerErrorCode = quote.errorHead == null ? null : A2Values.trimToNull(quote.errorHead.errorCode);
        if (headerErrorCode != null && !NON_ERROR_CODES.contains(headerErrorCode)) {
            SupplierStockInquiryResult.Status status = CONFIGURATION_HEADER_CODES.contains(headerErrorCode)
                    ? SupplierStockInquiryResult.Status.CONFIGURATION_ERROR
                    : SupplierStockInquiryResult.Status.SUPPLIER_UNAVAILABLE;
            // A document-level error is the vendor refusing the question, not an answer about any
            // article. No lines are carried: reporting them would attribute a per-article meaning to
            // a rejection that never looked at the articles.
            return new SupplierStockInquiryResult(status, List.of(), null);
        }

        List<SupplierStockInquiryResult.Line> lines = new ArrayList<>();
        for (StockInquiryA2Wire.QuoteLine wireLine : quote.orderLines) {
            if (wireLine != null && wireLine.orderedArticle != null) {
                lines.add(toCanonicalLine(wireLine.orderedArticle));
            }
        }

        // NOT_LISTED at document level means the vendor carries none of what we asked about — the
        // answer a single-article product page needs. With a mixed answer the document is OK and the
        // per-line statuses carry the detail, because "we stock one of these three" is not "not
        // listed".
        boolean noneListed = !lines.isEmpty()
                && lines.stream().allMatch(line -> line.status() == SupplierStockInquiryResult.LineStatus.NOT_LISTED);
        SupplierStockInquiryResult.Status status =
                noneListed ? SupplierStockInquiryResult.Status.NOT_LISTED : SupplierStockInquiryResult.Status.OK;

        // A2.5 states no snapshot instant of its own. asOf is stamped by the caller from the
        // exchange, since for a live inquiry "as of" is simply when we asked.
        return new SupplierStockInquiryResult(status, lines, null);
    }

    private SupplierStockInquiryResult.Line toCanonicalLine(StockInquiryA2Wire.QuoteArticle article) {
        String ean = article.articleIdentification == null
                ? null
                : A2Values.trimToNull(article.articleIdentification.eanuccArticleId);
        String supplierCode = article.articleIdentification == null
                ? null
                : A2Values.trimToNull(article.articleIdentification.manufacturersArticleId);

        String lineErrorCode = article.error == null ? null : A2Values.trimToNull(article.error.errorCode);
        boolean lineRejected = lineErrorCode != null && !NON_ERROR_CODES.contains(lineErrorCode);
        if (lineRejected && NOT_LISTED_LINE_CODES.contains(lineErrorCode)) {
            return new SupplierStockInquiryResult.Line(
                    ean, supplierCode, SupplierStockInquiryResult.LineStatus.NOT_LISTED, null, null, null, null);
        }
        if (lineRejected) {
            // Rejected for a reason that says nothing about whether the vendor stocks the article —
            // an invalid delivery date, a quantity we asked wrongly for. Nothing is stated, so
            // nothing is claimed.
            return new SupplierStockInquiryResult.Line(
                    ean, supplierCode, SupplierStockInquiryResult.LineStatus.NOT_ANSWERED, null, null, null, null);
        }

        String availability = A2Values.trimToNull(article.availability);
        LocalDate earliest = earliestDeliveryDate(article);
        Integer scheduled = scheduledQuantity(article);

        if ("3".equals(availability)) {
            // The vendor stated it has none. The one place a zero is a fact.
            return new SupplierStockInquiryResult.Line(
                    ean, supplierCode, SupplierStockInquiryResult.LineStatus.UNAVAILABLE, 0, null, null, null);
        }
        if ("1".equals(availability) || "2".equals(availability)) {
            // Code 2 is "not in the requested quantity — consider the announced actual quantity", so
            // the schedule is the answer for both codes. Where a vendor answered available but
            // scheduled nothing, the quantity stays null: it said yes without saying how many.
            return new SupplierStockInquiryResult.Line(
                    ean,
                    supplierCode,
                    SupplierStockInquiryResult.LineStatus.AVAILABLE,
                    scheduled,
                    earliest,
                    null,
                    null);
        }
        // Availability "0" (could not be determined) or absent.
        return new SupplierStockInquiryResult.Line(
                ean, supplierCode, SupplierStockInquiryResult.LineStatus.NOT_ANSWERED, null, earliest, null, null);
    }

    /**
     * Total quantity the vendor scheduled across its promised delivery dates.
     *
     * @return the total, or null when the vendor scheduled nothing readable — never 0, because a
     *     schedule we could not read is not a vendor saying it has none
     */
    @Nullable
    private static Integer scheduledQuantity(StockInquiryA2Wire.QuoteArticle article) {
        int total = 0;
        boolean stated = false;
        for (StockInquiryA2Wire.ScheduleDetail detail : article.scheduleDetails) {
            if (detail == null) {
                continue;
            }
            Integer quantity = A2Values.quantity(
                    detail.confirmedQuantity != null ? detail.confirmedQuantity : detail.availableQuantity);
            if (quantity != null) {
                total += quantity;
                stated = true;
            }
        }
        return stated ? total : null;
    }

    /** Earliest date the vendor promised any quantity, or null when it promised no dated quantity. */
    @Nullable
    private static LocalDate earliestDeliveryDate(StockInquiryA2Wire.QuoteArticle article) {
        LocalDate earliest = null;
        for (StockInquiryA2Wire.ScheduleDetail detail : article.scheduleDetails) {
            if (detail == null) {
                continue;
            }
            LocalDate date = A2Values.date(detail.deliveryDate);
            if (date != null && (earliest == null || date.isBefore(earliest))) {
                earliest = date;
            }
        }
        return earliest;
    }

    private static StockInquiryA2Wire.InquiryLine toWireLine(SupplierStockInquiry.Line line, int lineNumber) {
        StockInquiryA2Wire.InquiryLine wireLine = new StockInquiryA2Wire.InquiryLine();
        // The norm caps LineID at six characters and the canonical inquiry does not carry one, so
        // position in the request is the identity — which is also what the quote echoes back.
        wireLine.lineId = String.format("%06d", lineNumber);

        StockInquiryA2Wire.ArticleIdentification identification = new StockInquiryA2Wire.ArticleIdentification();
        identification.eanuccArticleId = A2Values.trimToNull(line.articleEan());
        identification.manufacturersArticleId = A2Values.trimToNull(line.supplierArticleCode());

        StockInquiryA2Wire.InquiryArticle article = new StockInquiryA2Wire.InquiryArticle();
        article.articleIdentification = identification;
        article.requestedQuantity = StockInquiryA2Wire.Quantity.of(line.requestedQuantity());
        wireLine.orderedArticle = article;
        return wireLine;
    }

    @NonNull
    private static String requireAgencyCode(@Nullable String agencyCode, String role) {
        String value = A2Values.trimToNull(agencyCode);
        if (value == null) {
            throw new StockInquiryEncodeException(
                    "EDIWheel A2.5 requires an agency code for the " + role + ", but the vendor profile states none");
        }
        return value;
    }
}
