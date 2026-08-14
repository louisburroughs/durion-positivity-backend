package com.positivity.supplier.internal.adapter.ediwheelc1;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Literal shape of the EDIWheel C1.1 order-status request and response documents
 * ({@code durion/docs/ediwheel/EDIWHEEL Order Status API PROD_1.yaml}).
 *
 * <p>Package-private (ADR-0051 §2). The response is the whole reason this capability carries
 * arrival information at all (#1318): the norm states delivery and despatch facts
 * <strong>per ordered article</strong>, nested two levels deep, and this class models that
 * nesting exactly as the vendor sends it —
 *
 * <pre>
 * OrderedArticle
 *   RequestedDeliveryDate                                    ← what we asked for
 *   ScheduleDetails[]                                        ← one per confirmed schedule
 *     DeliveryDate                                           ← what the vendor committed to
 *     ConfirmedQuantity / CancelledQuantity / OpenQuantity
 *     ShippedDetails
 *       ShippedQuantity
 *       DespatchedDetails
 *         ScheduledArticleDespatchDetails[]                  ← one per actual despatch
 *           DespatchDate                                     ← when it left
 *           DespatchAdviceReference/DocumentID               ← the ASN handle
 *           DespatchedQuantity
 * </pre>
 *
 * <p>Note where the despatch array actually hangs: under
 * {@code ScheduleDetails/ShippedDetails/DespatchedDetails}, not directly under
 * {@code ScheduleDetails}. That indirection is a wire concern the codec flattens away — the
 * canonical model says a schedule has despatches, because that is the fact — but it has to be
 * modelled faithfully here or the despatch dates simply never decode.
 */
final class OrderStatusC11Wire {

    private OrderStatusC11Wire() {}

    /** {@code ew:order_status_request} — the C1.1 query we send. */
    @XmlRootElement(name = "order_status_request")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "orderStatusRequestC11",
            propOrder = {"documentId", "variant", "orderStatusIndicator", "buyerParty", "referencedOrders"})
    static class OrderStatusRequest {

        /** Always {@code C1} for this norm family. */
        @XmlElement(name = "DocumentID", required = true)
        String documentId;

        /** Always {@code 1} for C1.1. */
        @XmlElement(name = "Variant", required = true)
        String variant;

        /**
         * Which orders to report on: {@code 0} open, {@code 1} open and cancelled, {@code 2} open,
         * cancelled and delivered.
         *
         * <p>We ask for {@code 2}. A narrower filter would make a delivered order disappear from
         * the answer, and "the vendor stopped mentioning it" is indistinguishable from "the vendor
         * never had it" — which under ADR-0052 §3 is the difference between a completed delivery
         * and a manual review.
         */
        @XmlElement(name = "OrderStatusIndicator")
        String orderStatusIndicator;

        @XmlElement(name = "BuyerParty", required = true)
        OrderC1Wire.Party buyerParty;

        @XmlElement(name = "ReferencedOrder")
        List<ReferencedOrderRequest> referencedOrders = new ArrayList<>();
    }

    /** The order being asked about, identified by the reference we sent when we created it. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "referencedOrderRequestC1",
            propOrder = {"supplierOrderNumber", "orderReference"})
    static class ReferencedOrderRequest {

        /** The vendor's own order number, when we know it — an attribute, never a key. */
        @XmlElement(name = "SupplierOrderNumber")
        OrderC1Wire.DocumentReference supplierOrderNumber;

        /** Our document id: the reference we sent as {@code CustomerReference} on the order. */
        @XmlElement(name = "OrderReference")
        OrderC1Wire.DocumentReference orderReference;
    }

    /** {@code ew:order_status} — the vendor's answer. */
    @XmlRootElement(name = "order_status")
    @XmlAccessorType(XmlAccessType.FIELD)
    static class OrderStatus {

        @XmlElement(name = "DocumentID")
        String documentId;

        @XmlElement(name = "Variant")
        String variant;

        /** Document-level outcome; {@code "0"} and {@code "1"} are the non-error codes. */
        @XmlElement(name = "ErrorHead")
        OrderC1Wire.ErrorBlock errorHead;

        /**
         * One entry per order the vendor found. An empty list is the vendor's way of saying it
         * holds no such order — the ADR-0052 §3 "does not know it" answer.
         */
        @XmlElement(name = "ReferencedOrder")
        List<ReferencedOrderResponse> referencedOrders = new ArrayList<>();
    }

    /** One order the vendor reports on. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ReferencedOrderResponse {

        @XmlElement(name = "Error")
        OrderC1Wire.ErrorBlock error;

        @XmlElement(name = "OrderDate")
        String orderDate;

        @XmlElement(name = "SupplierOrderNumber")
        OrderC1Wire.DocumentReference supplierOrderNumber;

        @XmlElement(name = "OrderReference")
        OrderC1Wire.DocumentReference orderReference;

        /**
         * Singular in the C1.1 schema, and modelled as a list anyway: the sibling A2.5 and C1.0
         * responses repeat it, several vendors repeat it here too, and a reader that binds one
         * element silently drops every line after the first.
         */
        @XmlElement(name = "OrderLine")
        List<StatusOrderLine> orderLines = new ArrayList<>();
    }

    /** One ordered line the vendor reports on. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class StatusOrderLine {

        @XmlElement(name = "LineID")
        String lineId;

        @XmlElement(name = "CustomerLineItemNumber")
        String customerLineItemNumber;

        @XmlElement(name = "OrderedArticle")
        StatusOrderedArticle orderedArticle;
    }

    /** The article of a status line: what was ordered, what was asked for, and what is scheduled. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class StatusOrderedArticle {

        @XmlElement(name = "ArticleIdentification")
        OrderC1Wire.ArticleIdentification articleIdentification;

        @XmlElement(name = "ArticleDescription")
        OrderC1Wire.ArticleDescription articleDescription;

        /** What we asked for. Never the same fact as a schedule's {@code DeliveryDate}. */
        @XmlElement(name = "RequestedDeliveryDate")
        String requestedDeliveryDate;

        @XmlElement(name = "Error")
        OrderC1Wire.ErrorBlock error;

        @XmlElement(name = "ScheduleDetails")
        List<StatusSchedule> scheduleDetails = new ArrayList<>();

        @XmlElement(name = "OrderedQuantity")
        OrderC1Wire.Quantity orderedQuantity;
    }

    /** One confirmed delivery schedule, with whatever has shipped against it. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class StatusSchedule {

        /** What the vendor committed to. Absent means it committed to no date, not that it took ours. */
        @XmlElement(name = "DeliveryDate")
        String deliveryDate;

        @XmlElement(name = "ConfirmedQuantity")
        OrderC1Wire.Quantity confirmedQuantity;

        @XmlElement(name = "CancelledQuantity")
        OrderC1Wire.Quantity cancelledQuantity;

        @XmlElement(name = "OpenQuantity")
        OrderC1Wire.Quantity openQuantity;

        @XmlElement(name = "BackorderedQuantity")
        OrderC1Wire.Quantity backorderedQuantity;

        @XmlElement(name = "ScheduledQuantity")
        OrderC1Wire.Quantity scheduledQuantity;

        @XmlElement(name = "ShippedDetails")
        ShippedDetails shippedDetails;
    }

    /** What has shipped against one schedule. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ShippedDetails {

        @XmlElement(name = "ShippedQuantity")
        OrderC1Wire.Quantity shippedQuantity;

        @XmlElement(name = "DespatchedDetails")
        DespatchedDetails despatchedDetails;
    }

    /** The wrapper the norm puts between a schedule and its despatches. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class DespatchedDetails {

        @XmlElement(name = "ScheduledArticleDespatchDetails")
        List<ScheduledArticleDespatch> scheduledArticleDespatchDetails = new ArrayList<>();
    }

    /** One actual despatch: when it left, under which despatch advice, and how much. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ScheduledArticleDespatch {

        @XmlElement(name = "DespatchDate")
        String despatchDate;

        /**
         * The despatch advice reference — the ASN handle. Retained verbatim and never parsed
         * (ADR-0013/0027): it is the operator's handle for a vendor or carrier query, and the join
         * key back to this despatch if an ASN feed ever appears.
         */
        @XmlElement(name = "DespatchAdviceReference")
        OrderC1Wire.DocumentReference despatchAdviceReference;

        @XmlElement(name = "DespatchedQuantity")
        OrderC1Wire.Quantity despatchedQuantity;
    }
}
