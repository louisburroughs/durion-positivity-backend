package com.positivity.supplier.internal.adapter.ediwheelc1;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Literal shape of the EDIWheel C1.0 order and order-response documents
 * ({@code durion/docs/ediwheel/EDIWHEEL Order Creation PROD_2 (2).yaml}).
 *
 * <p>Package-private (ADR-0051 §2): these are vendor types and only the codec in this package may
 * see them. They are JAXB classes rather than records because JAXB binds fields on a no-arg
 * constructor, and every numeric and date arrives as a string — parsed at this boundary so a
 * malformed vendor value never becomes a malformed canonical one.
 *
 * <p>Only the parts of the norm this capability uses are modelled. Elements a vendor sends that
 * are not declared here are ignored on the way in, which is what lets a vendor extend its
 * documents without breaking our reader.
 */
final class OrderC1Wire {

    private OrderC1Wire() {}

    /** {@code ew:order} — the document we send to create an order. */
    @XmlRootElement(name = "order")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "orderC1",
            propOrder = {"documentId", "variant", "customerReference", "buyerParty", "consignee", "orderLines"})
    static class Order {

        /** Always {@code C1} for this norm family. */
        @XmlElement(name = "DocumentID", required = true)
        String documentId;

        /** Always {@code 0} for C1.0. */
        @XmlElement(name = "Variant", required = true)
        String variant;

        /**
         * Our reference for this document — the wire {@code DocumentID} derived from the
         * transmission intent (ADR-0052 §1). This is the field a vendor's deduplication, where it
         * has one, keys on, and the field an order-status query later looks the order up by.
         */
        @XmlElement(name = "CustomerReference")
        DocumentReference customerReference;

        @XmlElement(name = "BuyerParty", required = true)
        Party buyerParty;

        /** The delivery party: the vendor's account for the receiving location. */
        @XmlElement(name = "Consignee")
        Party consignee;

        @XmlElement(name = "OrderLine")
        List<OrderLine> orderLines = new ArrayList<>();
    }

    /** {@code ew:order_response} — the vendor's answer to an order. */
    @XmlRootElement(name = "order_response")
    @XmlAccessorType(XmlAccessType.FIELD)
    static class OrderResponse {

        @XmlElement(name = "DocumentID")
        String documentId;

        @XmlElement(name = "Variant")
        String variant;

        /** Document-level outcome; {@code "0"} and {@code "1"} are the non-error codes. */
        @XmlElement(name = "ErrorHead")
        ErrorBlock errorHead;

        /** Our reference, echoed back. */
        @XmlElement(name = "CustomerReference")
        DocumentReference customerReference;

        /** The vendor's own order number. An attribute, never a key (ADR-0013/0027). */
        @XmlElement(name = "SupplierOrderNumber")
        DocumentReference supplierOrderNumber;

        @XmlElement(name = "OrderLine")
        List<OrderResponseLine> orderLines = new ArrayList<>();
    }

    /** A party identification: account number plus the agency that assigned it. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "partyC1",
            propOrder = {"partyId", "agencyCode"})
    static class Party {

        @XmlElement(name = "PartyID", required = true)
        String partyId;

        @XmlElement(name = "AgencyCode", required = true)
        String agencyCode;

        static Party of(String partyId, String agencyCode) {
            Party party = new Party();
            party.partyId = partyId;
            party.agencyCode = agencyCode;
            return party;
        }
    }

    /** The norm's recurring "a reference is an object with a DocumentID" shape. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "documentReferenceC1")
    static class DocumentReference {

        @XmlElement(name = "DocumentID", required = true)
        String documentId;

        @XmlElement(name = "LineID")
        String lineId;

        static DocumentReference of(String documentId) {
            DocumentReference reference = new DocumentReference();
            reference.documentId = documentId;
            return reference;
        }
    }

    /** An error code with optional text, at document or line level. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "errorBlockC1")
    static class ErrorBlock {

        @XmlElement(name = "ErrorCode")
        String errorCode;

        @XmlElement(name = "ErrorText")
        String errorText;
    }

    /** A quantity: the norm wraps every numeric in an element of its own. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "quantityC1")
    static class Quantity {

        @XmlElement(name = "QuantityValue", required = true)
        String quantityValue;

        static Quantity of(int value) {
            Quantity quantity = new Quantity();
            quantity.quantityValue = Integer.toString(value);
            return quantity;
        }
    }

    /** One line of an outbound order. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "orderLineC1",
            propOrder = {"lineId", "orderedArticle"})
    static class OrderLine {

        @XmlElement(name = "LineID", required = true)
        String lineId;

        @XmlElement(name = "OrderedArticle", required = true)
        OrderedArticle orderedArticle;
    }

    /** The article of an outbound order line. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "orderedArticleC1",
            propOrder = {"articleIdentification", "requestedDeliveryDate", "requestedQuantity"})
    static class OrderedArticle {

        @XmlElement(name = "ArticleIdentification", required = true)
        ArticleIdentification articleIdentification;

        /**
         * The delivery date we ask for. What the vendor confirms comes back separately on the
         * response and the status feed; the two are never merged (#1318).
         */
        @XmlElement(name = "RequestedDeliveryDate")
        String requestedDeliveryDate;

        @XmlElement(name = "RequestedQuantity", required = true)
        Quantity requestedQuantity;
    }

    /** Article identity in the three forms the norm allows. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "articleIdentificationC1",
            propOrder = {"buyersArticleId", "manufacturersArticleId", "eanuccArticleId"})
    static class ArticleIdentification {

        @XmlElement(name = "BuyersArticleID")
        String buyersArticleId;

        @XmlElement(name = "ManufacturersArticleID")
        String manufacturersArticleId;

        @XmlElement(name = "EANUCCArticleID")
        String eanuccArticleId;
    }

    /** One line of the vendor's order response. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class OrderResponseLine {

        @XmlElement(name = "LineID")
        String lineId;

        @XmlElement(name = "OrderedArticle")
        OrderResponseArticle orderedArticle;
    }

    /** The article of a response line, with the vendor's confirmation of it. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class OrderResponseArticle {

        @XmlElement(name = "ArticleIdentification")
        ArticleIdentification articleIdentification;

        @XmlElement(name = "ArticleDescription")
        ArticleDescription articleDescription;

        @XmlElement(name = "RequestedDeliveryDate")
        String requestedDeliveryDate;

        @XmlElement(name = "OrderReference")
        DocumentReference orderReference;

        @XmlElement(name = "Error")
        ErrorBlock error;

        /**
         * The schedules the vendor committed to. Plural in the norm and plural here: a vendor
         * splitting a line across dates is ordinary, and collapsing it would have to invent a
         * single date the vendor never gave.
         */
        @XmlElement(name = "ScheduleDetails")
        List<ResponseSchedule> scheduleDetails = new ArrayList<>();

        @XmlElement(name = "KilledQuantity")
        Quantity killedQuantity;

        @XmlElement(name = "OrderedQuantity")
        Quantity orderedQuantity;
    }

    /** Vendor article description text. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ArticleDescription {

        @XmlElement(name = "ArticleDescriptionText")
        String articleDescriptionText;
    }

    /** One confirmed schedule on an order response: a date and the quantity for it. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ResponseSchedule {

        @XmlElement(name = "DeliveryDate")
        String deliveryDate;

        @XmlElement(name = "ConfirmedQuantity")
        Quantity confirmedQuantity;
    }
}
