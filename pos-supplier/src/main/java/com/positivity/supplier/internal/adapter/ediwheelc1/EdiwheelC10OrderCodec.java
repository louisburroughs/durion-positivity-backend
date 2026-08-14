package com.positivity.supplier.internal.adapter.ediwheelc1;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierOrderResult;
import com.positivity.supplier.internal.domain.model.SupplierPurchaseOrder;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import jakarta.xml.bind.JAXBContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EDIWheel Order Creation C1.0 codec: {@code POST /C1_0/create} (CAP-320 #1226).
 *
 * <p>Registered under {@code (ORDER_CREATE, EDIWHEEL_C1, C1_0)} (ADR-0051 §3). Like every codec
 * it performs no I/O: it produces a transport-free request spec and maps a response body to the
 * canonical result (ADR-0051 §5).
 *
 * <h2>The request is never idempotent</h2>
 *
 * {@link SupplierRequestSpec#idempotent()} is false and must stay false. ADR-0052 §5 permits
 * free retry only for checkpointed-window reads; an order create is the one call in this module
 * where an automatic retry after transmission puts real tyres on a real truck twice.
 *
 * <h2>The document id is ours, and it is stable</h2>
 *
 * {@code CustomerReference/DocumentID} carries {@link SupplierPurchaseOrder#documentId()},
 * derived deterministically from the transmission intent (ADR-0052 §1). The codec never
 * generates it, never falls back when it is missing, and never varies it between attempts — a
 * retry of one intent presents the same reference, which is what gives a vendor's deduplication
 * something to work with and what makes the later status query answerable.
 *
 * <h2>Encoding failures happen before the network</h2>
 *
 * A missing agency code or an unidentifiable line raises {@link OrderEncodeException} from
 * {@link #encodeOrder}, which the orchestrator calls before the attempt leaves {@code PENDING}.
 * A configuration defect therefore costs a re-dispatch after a fix, not a manual reconciliation.
 */
@Component
public class EdiwheelC10OrderCodec implements SupplierAdapterCodec {

    /** Document id of the C1 norm family, as stated in the spec's enum. */
    private static final String DOCUMENT_ID = "C1";

    /** Variant of the C1.0 order document. */
    private static final String VARIANT = "0";

    /**
     * Vendor error codes that are <em>not</em> errors. The spec's header enum starts with these
     * two, and every other value is a refusal of the document.
     */
    private static final List<String> NON_ERROR_CODES = List.of("0", "1");

    private final JAXBContext orderContext = C1XmlSupport.contextFor(OrderC1Wire.Order.class);
    private final JAXBContext orderResponseContext = C1XmlSupport.contextFor(OrderC1Wire.OrderResponse.class);

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.ORDER_CREATE;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_C1;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.C1_0;
    }

    /**
     * Builds the order-create call.
     *
     * @param order the canonical order, carrying the intent-derived document id
     * @param partyContext buyer identity, plus the delivery account when the order has a
     *     consignee
     * @param deliveryAccountNumber the vendor's account number for the receiving location, or
     *     null when the profile addresses no consignee
     * @param deliveryAgencyCode agency code of that delivery account
     * @return a transport-free request spec
     * @throws OrderEncodeException when the canonical order cannot be expressed in C1.0
     */
    @NonNull
    public SupplierRequestSpec buildRequest(
            @NonNull SupplierPurchaseOrder order,
            @NonNull PartyContext partyContext,
            @Nullable String deliveryAccountNumber,
            @Nullable String deliveryAgencyCode) {
        String body = encodeOrder(order, partyContext, deliveryAccountNumber, deliveryAgencyCode);
        return new SupplierRequestSpec("POST", null, Map.of(), body, "application/xml", "application/xml", false);
    }

    /**
     * Serialises a canonical order as a C1.0 {@code order} document.
     *
     * @throws OrderEncodeException when a required vendor field has no canonical source
     */
    @NonNull
    public String encodeOrder(
            @NonNull SupplierPurchaseOrder order,
            @NonNull PartyContext partyContext,
            @Nullable String deliveryAccountNumber,
            @Nullable String deliveryAgencyCode) {
        OrderC1Wire.Order document = new OrderC1Wire.Order();
        document.documentId = DOCUMENT_ID;
        document.variant = VARIANT;
        document.customerReference = OrderC1Wire.DocumentReference.of(order.documentId());
        document.buyerParty = OrderC1Wire.Party.of(
                partyContext.billingAccountNumber(),
                requireAgencyCode(partyContext.billingAgencyCode(), "billing account"));

        if (deliveryAccountNumber != null && !deliveryAccountNumber.isBlank()) {
            document.consignee = OrderC1Wire.Party.of(
                    deliveryAccountNumber, requireAgencyCode(deliveryAgencyCode, "delivery account"));
        }

        for (SupplierPurchaseOrder.Line line : order.lines()) {
            document.orderLines.add(toWireLine(line));
        }
        return C1XmlSupport.marshal(orderContext, document);
    }

    /**
     * Decodes a C1.0 {@code order_response} into the canonical result.
     *
     * @param body response body as received
     * @return the vendor's decision, with per-line detail
     * @throws OrderDecodeException when the body is not a readable C1.0 order response
     */
    @NonNull
    public SupplierOrderResult decodeResponse(@Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new OrderDecodeException("Order response body was empty");
        }
        OrderC1Wire.OrderResponse response =
                C1XmlSupport.unmarshal(orderResponseContext, OrderC1Wire.OrderResponse.class, body);

        String headerErrorCode = response.errorHead == null ? null : trimToNull(response.errorHead.errorCode);
        String headerErrorText = response.errorHead == null ? null : trimToNull(response.errorHead.errorText);
        boolean rejected = headerErrorCode != null && !NON_ERROR_CODES.contains(headerErrorCode);

        List<SupplierOrderResult.Line> lines = new ArrayList<>(response.orderLines.size());
        for (OrderC1Wire.OrderResponseLine wireLine : response.orderLines) {
            if (wireLine != null) {
                lines.add(toResultLine(wireLine));
            }
        }

        return new SupplierOrderResult(
                rejected ? SupplierOrderResult.Status.REJECTED : SupplierOrderResult.Status.CONFIRMED,
                response.supplierOrderNumber == null ? null : trimToNull(response.supplierOrderNumber.documentId),
                headerErrorText,
                headerErrorCode,
                lines);
    }

    private static OrderC1Wire.OrderLine toWireLine(SupplierPurchaseOrder.Line line) {
        OrderC1Wire.ArticleIdentification identification = new OrderC1Wire.ArticleIdentification();
        identification.eanuccArticleId = trimToNull(line.articleEan());
        identification.manufacturersArticleId = trimToNull(line.supplierArticleCode());
        if (identification.eanuccArticleId == null && identification.manufacturersArticleId == null) {
            // Defensive: the canonical record enforces this too. Reaching the vendor with an
            // unidentifiable line would earn a rejection of the whole document, not just the line.
            throw new OrderEncodeException(
                    "Order line " + line.lineNumber() + " states no article identity (EAN or supplier article code)");
        }

        OrderC1Wire.OrderedArticle article = new OrderC1Wire.OrderedArticle();
        article.articleIdentification = identification;
        article.requestedDeliveryDate = line.requestedDeliveryDate() == null
                ? null
                : line.requestedDeliveryDate().toString();
        article.requestedQuantity = OrderC1Wire.Quantity.of(line.quantity());

        OrderC1Wire.OrderLine wireLine = new OrderC1Wire.OrderLine();
        // The norm's LineID is a 6-character string; zero-padding keeps our line numbers stable and
        // sortable in the vendor's own screens, and it is what comes back on the status feed.
        wireLine.lineId = String.format("%06d", line.lineNumber());
        wireLine.orderedArticle = article;
        return wireLine;
    }

    private static SupplierOrderResult.Line toResultLine(OrderC1Wire.OrderResponseLine wireLine) {
        OrderC1Wire.OrderResponseArticle article = wireLine.orderedArticle;
        OrderC1Wire.ArticleIdentification identity = article == null ? null : article.articleIdentification;
        OrderC1Wire.ErrorBlock error = article == null ? null : article.error;

        List<SupplierDeliverySchedule> schedules = new ArrayList<>();
        if (article != null) {
            for (OrderC1Wire.ResponseSchedule schedule : article.scheduleDetails) {
                if (schedule != null) {
                    schedules.add(SupplierDeliverySchedule.confirmed(
                            C1Values.date(schedule.deliveryDate), C1Values.quantity(schedule.confirmedQuantity)));
                }
            }
        }

        return new SupplierOrderResult.Line(
                trimToNull(wireLine.lineId),
                identity == null ? null : trimToNull(identity.eanuccArticleId),
                identity == null ? null : trimToNull(identity.manufacturersArticleId),
                article == null ? null : C1Values.quantity(article.orderedQuantity),
                // A response line with no ScheduleDetails states no confirmed quantity, and that is
                // left null rather than summed to zero: the vendor has not said "none", it has said
                // nothing yet.
                schedules.isEmpty() ? null : sumConfirmed(schedules),
                article == null ? null : C1Values.quantity(article.killedQuantity),
                schedules,
                error == null ? null : trimToNull(error.errorCode),
                error == null ? null : trimToNull(error.errorText));
    }

    /**
     * Total confirmed across a line's schedules.
     *
     * <p>Schedules that state no quantity contribute nothing rather than zero, and a line whose
     * schedules all stay silent totals to null — the vendor gave dates without quantities, which
     * is not a confirmation of none.
     */
    @Nullable
    private static Integer sumConfirmed(List<SupplierDeliverySchedule> schedules) {
        Integer total = null;
        for (SupplierDeliverySchedule schedule : schedules) {
            if (schedule.confirmedQuantity() != null) {
                total = (total == null ? 0 : total) + schedule.confirmedQuantity();
            }
        }
        return total;
    }

    @NonNull
    private static String requireAgencyCode(@Nullable String agencyCode, String what) {
        String value = trimToNull(agencyCode);
        if (value == null) {
            // The norm makes AgencyCode mandatory on every party. Failing here, before the network,
            // turns a configuration gap into a fixable PENDING attempt rather than a vendor
            // rejection an operator has to interpret.
            throw new OrderEncodeException(
                    "The " + what + " has no agency code; EDIWheel C1.0 requires one on every party");
        }
        return value;
    }

    @Nullable
    private static String trimToNull(@Nullable String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
