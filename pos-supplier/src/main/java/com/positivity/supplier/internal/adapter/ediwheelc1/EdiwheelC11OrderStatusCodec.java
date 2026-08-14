package com.positivity.supplier.internal.adapter.ediwheelc1;

import com.positivity.supplier.internal.domain.model.PartyContext;
import com.positivity.supplier.internal.domain.model.ProtocolFamily;
import com.positivity.supplier.internal.domain.model.ProtocolVersion;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierDeliverySchedule;
import com.positivity.supplier.internal.domain.model.SupplierDespatch;
import com.positivity.supplier.internal.domain.model.SupplierOrderStatusResult;
import com.positivity.supplier.internal.domain.model.SupplierRequestSpec;
import com.positivity.supplier.internal.spi.SupplierAdapterCodec;
import jakarta.xml.bind.JAXBContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * EDIWheel Order Status C1.1 codec: {@code POST /C1_1/status} (CAP-320 #1226, extended by
 * #1318).
 *
 * <p>Registered under {@code (ORDER_STATUS, EDIWHEEL_C1, C1_1)} (ADR-0051 §3). No I/O
 * (ADR-0051 §5).
 *
 * <h2>Two jobs</h2>
 *
 * <ol>
 *   <li><strong>Reconciliation (ADR-0052 §3).</strong> After an ambiguous order create, the
 *       vendor's answer to "do you hold this document" decides between applying the recorded
 *       state and {@code MANUAL_REVIEW}. This is why an unmatched answer decodes to
 *       {@link SupplierOrderStatusResult.Status#NOT_FOUND} rather than to a guess: NOT_FOUND
 *       sends a human to look, while a wrong CONFIRMED silently closes a transmission the vendor
 *       may never have received.
 *   <li><strong>Arrival expectations (#1318).</strong> Delivery and despatch facts arrive here
 *       per ordered article, and they are carried through in full: multiple schedules per line,
 *       multiple despatches per schedule, the despatch advice (ASN) reference on each. Nothing
 *       is flattened to one date per order, because a line with two confirmed deliveries has two
 *       arrival dates and receiving needs both.
 * </ol>
 *
 * <h2>Three date kinds, never coalesced</h2>
 *
 * {@code RequestedDeliveryDate} (ours), {@code ScheduleDetails/DeliveryDate} (the vendor's
 * commitment) and {@code DespatchDate} (when it left) map to three separately nullable canonical
 * fields. A missing confirmed date is never filled from the requested one.
 *
 * <h2>Where the despatches actually live</h2>
 *
 * The norm nests them under {@code ScheduleDetails/ShippedDetails/DespatchedDetails/}
 * {@code ScheduledArticleDespatchDetails}, not directly under the schedule. The codec flattens
 * that wrapper away — a schedule has despatches, and the intermediate elements carry no fact of
 * their own beyond {@code ShippedQuantity}, which is kept on the schedule.
 */
@Component
public class EdiwheelC11OrderStatusCodec implements SupplierAdapterCodec {

    /** Document id of the C1 norm family. */
    private static final String DOCUMENT_ID = "C1";

    /** Variant of the C1.1 status document. */
    private static final String VARIANT = "1";

    /**
     * Ask for open, cancelled <em>and</em> delivered orders.
     *
     * <p>Anything narrower makes a completed order vanish from the answer, and "the vendor stopped
     * listing it" is indistinguishable from "the vendor never had it" — which is the difference
     * between a delivered purchase order and a manual review (ADR-0052 §3).
     */
    private static final String STATUS_INDICATOR_ALL = "2";

    /** Error codes that are not errors, per the spec's enums. */
    private static final List<String> NON_ERROR_CODES = List.of("0", "1");

    private final JAXBContext requestContext = C1XmlSupport.contextFor(OrderStatusC11Wire.OrderStatusRequest.class);
    private final JAXBContext responseContext = C1XmlSupport.contextFor(OrderStatusC11Wire.OrderStatus.class);

    @Override
    @NonNull
    public SupplierCapability capability() {
        return SupplierCapability.ORDER_STATUS;
    }

    @Override
    @NonNull
    public ProtocolFamily family() {
        return ProtocolFamily.EDIWHEEL_C1;
    }

    @Override
    @NonNull
    public ProtocolVersion version() {
        return ProtocolVersion.C1_1;
    }

    /**
     * Builds a status query for one order.
     *
     * @param documentId our wire document id, as sent on the order's {@code CustomerReference}
     * @param supplierOrderNumber the vendor's own order number when known; sent alongside so a
     *     vendor that indexes on its own reference can answer too
     * @param partyContext buyer identity from the profile's billing account
     * @return a transport-free request spec
     * @throws OrderEncodeException when the billing account states no agency code
     */
    @NonNull
    public SupplierRequestSpec buildRequest(
            @NonNull String documentId, @Nullable String supplierOrderNumber, @NonNull PartyContext partyContext) {
        String body = encodeRequest(documentId, supplierOrderNumber, partyContext);
        // Idempotent: a status query reads vendor state and creates none, so ADR-0052 §5 permits a
        // retry after transmission. This is the safe half of the pair — the create next door is
        // emphatically not idempotent.
        return new SupplierRequestSpec("POST", null, Map.of(), body, "application/xml", "application/xml", true);
    }

    /** Serialises the C1.1 {@code order_status_request} document. */
    @NonNull
    public String encodeRequest(
            @NonNull String documentId, @Nullable String supplierOrderNumber, @NonNull PartyContext partyContext) {
        Objects.requireNonNull(documentId, "documentId must not be null");
        if (documentId.isBlank()) {
            throw new OrderEncodeException("documentId must not be blank on an order-status query");
        }

        OrderStatusC11Wire.ReferencedOrderRequest referenced = new OrderStatusC11Wire.ReferencedOrderRequest();
        referenced.orderReference = OrderC1Wire.DocumentReference.of(documentId);
        String vendorNumber = C1Values.trimToNull(supplierOrderNumber);
        if (vendorNumber != null) {
            referenced.supplierOrderNumber = OrderC1Wire.DocumentReference.of(vendorNumber);
        }

        OrderStatusC11Wire.OrderStatusRequest request = new OrderStatusC11Wire.OrderStatusRequest();
        request.documentId = DOCUMENT_ID;
        request.variant = VARIANT;
        request.orderStatusIndicator = STATUS_INDICATOR_ALL;
        request.buyerParty = OrderC1Wire.Party.of(
                partyContext.billingAccountNumber(), requireAgencyCode(partyContext.billingAgencyCode()));
        request.referencedOrders.add(referenced);

        return C1XmlSupport.marshal(requestContext, request);
    }

    /**
     * Decodes a C1.1 {@code order_status} response for one queried document.
     *
     * @param documentId the document id that was queried; the answer is matched against it
     * @param body response body as received
     * @return the canonical status plus any vendor values that were stated but unreadable
     * @throws OrderDecodeException when the body is not a readable C1.1 status document
     */
    @NonNull
    public Decoded decode(@NonNull String documentId, @Nullable String body) {
        if (body == null || body.isBlank()) {
            throw new OrderDecodeException("Order status response body was empty");
        }
        OrderStatusC11Wire.OrderStatus document =
                C1XmlSupport.unmarshal(responseContext, OrderStatusC11Wire.OrderStatus.class, body);

        List<String> unmapped = new ArrayList<>();
        String headerErrorCode = document.errorHead == null ? null : C1Values.trimToNull(document.errorHead.errorCode);
        if (headerErrorCode != null && !NON_ERROR_CODES.contains(headerErrorCode)) {
            // A document-level error is the vendor refusing the query, not an answer about the
            // order. Reporting it as NOT_FOUND would let a malformed query masquerade as "the
            // vendor never received the order".
            throw new OrderDecodeException("Order status response carries vendor header error code " + headerErrorCode);
        }

        OrderStatusC11Wire.ReferencedOrderResponse matched = match(document, documentId, unmapped);
        if (matched == null) {
            return new Decoded(SupplierOrderStatusResult.notFound(documentId), List.copyOf(unmapped));
        }

        String orderErrorCode = matched.error == null ? null : C1Values.trimToNull(matched.error.errorCode);
        String orderErrorText = matched.error == null ? null : C1Values.trimToNull(matched.error.errorText);
        boolean rejected = orderErrorCode != null && !NON_ERROR_CODES.contains(orderErrorCode);

        List<SupplierOrderStatusResult.Line> lines = new ArrayList<>();
        for (OrderStatusC11Wire.StatusOrderLine wireLine : matched.orderLines) {
            if (wireLine != null) {
                lines.add(toLine(wireLine, unmapped));
            }
        }

        SupplierOrderStatusResult.Status status;
        if (rejected) {
            status = SupplierOrderStatusResult.Status.REJECTED;
        } else if (hasAnyConfirmedSchedule(lines)) {
            status = SupplierOrderStatusResult.Status.CONFIRMED;
        } else {
            // The vendor holds the order but has scheduled nothing against it yet. Distinct from
            // CONFIRMED on purpose: for reconciliation both mean "the vendor has it, do not
            // re-send", but for a purchase-order timeline they are "accepted" and "acknowledged",
            // and only one of them justifies telling receiving something is coming.
            status = SupplierOrderStatusResult.Status.IN_PROGRESS;
        }

        if (C1Values.isUnreadableDate(matched.orderDate)) {
            unmapped.add("ReferencedOrder/OrderDate");
        }

        return new Decoded(
                new SupplierOrderStatusResult(
                        documentId,
                        status,
                        matched.supplierOrderNumber == null
                                ? null
                                : C1Values.trimToNull(matched.supplierOrderNumber.documentId),
                        orderErrorText,
                        orderErrorCode,
                        C1Values.date(matched.orderDate),
                        lines),
                List.copyOf(unmapped));
    }

    /**
     * Finds the answer that is about the order we asked about.
     *
     * <p>Matching is on our own reference, not on position: a vendor may answer with several
     * orders, and picking the first would attribute one order's delivery dates to another. The one
     * concession is a single answer that echoes no reference at all — that is a vendor answering a
     * single-reference query tersely, and it is recorded as an assumption rather than made
     * silently.
     *
     * @return the matching answer, or null when the vendor's answer is about no order of ours
     */
    private static OrderStatusC11Wire.@Nullable ReferencedOrderResponse match(
            OrderStatusC11Wire.OrderStatus document, String documentId, List<String> unmapped) {
        List<OrderStatusC11Wire.ReferencedOrderResponse> answers =
                document.referencedOrders.stream().filter(Objects::nonNull).toList();
        if (answers.isEmpty()) {
            return null;
        }
        for (OrderStatusC11Wire.ReferencedOrderResponse answer : answers) {
            String reference =
                    answer.orderReference == null ? null : C1Values.trimToNull(answer.orderReference.documentId);
            if (reference != null && reference.equalsIgnoreCase(documentId)) {
                return answer;
            }
        }
        if (answers.size() == 1 && answers.getFirst().orderReference == null) {
            unmapped.add("ReferencedOrder/OrderReference (absent; single answer assumed to be ours)");
            return answers.getFirst();
        }
        unmapped.add("ReferencedOrder/OrderReference (no answer echoed the queried document id)");
        return null;
    }

    private static SupplierOrderStatusResult.Line toLine(
            OrderStatusC11Wire.StatusOrderLine wireLine, List<String> unmapped) {
        OrderStatusC11Wire.StatusOrderedArticle article = wireLine.orderedArticle;
        OrderC1Wire.ArticleIdentification identity = article == null ? null : article.articleIdentification;
        OrderC1Wire.ErrorBlock error = article == null ? null : article.error;

        List<SupplierDeliverySchedule> schedules = new ArrayList<>();
        if (article != null) {
            for (OrderStatusC11Wire.StatusSchedule schedule : article.scheduleDetails) {
                if (schedule != null) {
                    schedules.add(toSchedule(schedule, unmapped));
                }
            }
            if (C1Values.isUnreadableDate(article.requestedDeliveryDate)) {
                unmapped.add("OrderedArticle/RequestedDeliveryDate");
            }
            if (C1Values.isUnreadableQuantity(article.orderedQuantity)) {
                unmapped.add("OrderedArticle/OrderedQuantity");
            }
        }

        return new SupplierOrderStatusResult.Line(
                C1Values.trimToNull(wireLine.lineId),
                C1Values.trimToNull(wireLine.customerLineItemNumber),
                identity == null ? null : C1Values.trimToNull(identity.eanuccArticleId),
                identity == null ? null : C1Values.trimToNull(identity.manufacturersArticleId),
                identity == null ? null : C1Values.trimToNull(identity.buyersArticleId),
                article == null || article.articleDescription == null
                        ? null
                        : C1Values.trimToNull(article.articleDescription.articleDescriptionText),
                article == null ? null : C1Values.quantity(article.orderedQuantity),
                article == null ? null : C1Values.date(article.requestedDeliveryDate),
                schedules,
                error == null ? null : C1Values.trimToNull(error.errorCode),
                error == null ? null : C1Values.trimToNull(error.errorText));
    }

    private static SupplierDeliverySchedule toSchedule(
            OrderStatusC11Wire.StatusSchedule schedule, List<String> unmapped) {
        List<SupplierDespatch> despatches = new ArrayList<>();
        Integer shippedQuantity = null;

        OrderStatusC11Wire.ShippedDetails shipped = schedule.shippedDetails;
        if (shipped != null) {
            shippedQuantity = C1Values.quantity(shipped.shippedQuantity);
            if (C1Values.isUnreadableQuantity(shipped.shippedQuantity)) {
                unmapped.add("ScheduleDetails/ShippedDetails/ShippedQuantity");
            }
            if (shipped.despatchedDetails != null) {
                for (OrderStatusC11Wire.ScheduledArticleDespatch despatch :
                        shipped.despatchedDetails.scheduledArticleDespatchDetails) {
                    if (despatch != null) {
                        despatches.add(toDespatch(despatch, unmapped));
                    }
                }
            }
        }

        if (C1Values.isUnreadableDate(schedule.deliveryDate)) {
            unmapped.add("ScheduleDetails/DeliveryDate");
        }

        return new SupplierDeliverySchedule(
                C1Values.date(schedule.deliveryDate),
                C1Values.quantity(schedule.confirmedQuantity),
                C1Values.quantity(schedule.cancelledQuantity),
                C1Values.quantity(schedule.openQuantity),
                C1Values.quantity(schedule.backorderedQuantity),
                C1Values.quantity(schedule.scheduledQuantity),
                shippedQuantity,
                despatches);
    }

    private static SupplierDespatch toDespatch(
            OrderStatusC11Wire.ScheduledArticleDespatch despatch, List<String> unmapped) {
        if (C1Values.isUnreadableDate(despatch.despatchDate)) {
            // Worth surfacing: an unparseable despatch date and an absent one both render as "no
            // date", and only this record distinguishes a vendor format change from a vendor
            // silence.
            unmapped.add("ScheduledArticleDespatchDetails/DespatchDate");
        }
        return new SupplierDespatch(
                C1Values.date(despatch.despatchDate),
                despatch.despatchAdviceReference == null
                        ? null
                        : C1Values.trimToNull(despatch.despatchAdviceReference.documentId),
                despatch.despatchAdviceReference == null
                        ? null
                        : C1Values.trimToNull(despatch.despatchAdviceReference.lineId),
                C1Values.quantity(despatch.despatchedQuantity));
    }

    private static boolean hasAnyConfirmedSchedule(List<SupplierOrderStatusResult.Line> lines) {
        return lines.stream().anyMatch(line -> !line.schedules().isEmpty());
    }

    @NonNull
    private static String requireAgencyCode(@Nullable String agencyCode) {
        String value = C1Values.trimToNull(agencyCode);
        if (value == null) {
            throw new OrderEncodeException(
                    "The billing account has no agency code; EDIWheel C1.1 requires one on BuyerParty");
        }
        return value;
    }

    /**
     * A decoded status document.
     *
     * @param result the canonical status
     * @param unmappedFields vendor values that were stated but could not be read, recorded per
     *     exchange so nothing is silently dropped (ADR-0051 §4)
     */
    public record Decoded(
            @NonNull SupplierOrderStatusResult result,
            @NonNull List<String> unmappedFields) {

        public Decoded {
            Objects.requireNonNull(result, "result must not be null");
            Objects.requireNonNull(unmappedFields, "unmappedFields must not be null");
            unmappedFields = List.copyOf(unmappedFields);
        }

        /** Whether the vendor stated anything this codec could not read. */
        public boolean isComplete() {
            return unmappedFields.isEmpty();
        }
    }
}
