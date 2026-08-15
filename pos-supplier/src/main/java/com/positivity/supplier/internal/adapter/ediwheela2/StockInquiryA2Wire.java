package com.positivity.supplier.internal.adapter.ediwheela2;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.XmlType;
import java.util.ArrayList;
import java.util.List;

/**
 * Literal shape of the EDIWheel A2.5 stock-inquiry documents
 * ({@code durion/docs/ediwheel/Swagger_UPX-Stock Inquiry PROD_1_2.yaml}).
 *
 * <p>Package-private (ADR-0051 §2): these are vendor types and only the codec in this package may
 * see them. They are JAXB classes rather than records because JAXB binds fields on a no-arg
 * constructor, and every numeric arrives as a string — parsed at this boundary so a malformed
 * vendor value never becomes a malformed canonical one.
 *
 * <p>Only the parts of the norm this capability uses are modelled. Elements a vendor sends that are
 * not declared here are ignored on the way in, which is what lets a vendor extend its documents
 * without breaking our reader.
 */
final class StockInquiryA2Wire {

    private StockInquiryA2Wire() {}

    /** {@code ew:inquiry_A2} — the document we send to ask about availability. */
    @XmlRootElement(name = "inquiry_A2")
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "inquiryA2",
            propOrder = {"documentId", "variant", "customerReference", "buyerParty", "consignee", "orderLines"})
    static class Inquiry {

        /** Always {@code A2} for this norm family. */
        @XmlElement(name = "DocumentID", required = true)
        String documentId;

        /** Always {@code 5} for A2.5. */
        @XmlElement(name = "Variant", required = true)
        String variant;

        /** Our reference for this inquiry, echoed back on the quote. */
        @XmlElement(name = "CustomerReference")
        DocumentReference customerReference;

        @XmlElement(name = "BuyerParty", required = true)
        Party buyerParty;

        /**
         * The delivery party: the vendor's account for the receiving location.
         *
         * <p>Availability is location-specific — the same article can be in stock for one
         * consignee and not another — which is why the canonical read requires a delivery location
         * and why the inquiry cache may never be keyed without one.
         */
        @XmlElement(name = "Consignee")
        Party consignee;

        @XmlElement(name = "OrderLine")
        List<InquiryLine> orderLines = new ArrayList<>();
    }

    /** {@code ew:quote_A2} — the vendor's answer. */
    @XmlRootElement(name = "quote_A2")
    @XmlAccessorType(XmlAccessType.FIELD)
    static class Quote {

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

        @XmlElement(name = "BuyerParty")
        Party buyerParty;

        @XmlElement(name = "Consignee")
        Party consignee;

        @XmlElement(name = "OrderLine")
        List<QuoteLine> orderLines = new ArrayList<>();
    }

    /** {@code ew:OrderLine} of an inquiry. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "inquiryLineA2",
            propOrder = {"lineId", "orderedArticle"})
    static class InquiryLine {

        @XmlElement(name = "LineID", required = true)
        String lineId;

        @XmlElement(name = "OrderedArticle", required = true)
        InquiryArticle orderedArticle;
    }

    /** {@code ew:OrderedArticle} of an inquiry line. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "inquiryArticleA2",
            propOrder = {"articleIdentification", "requestedQuantity"})
    static class InquiryArticle {

        @XmlElement(name = "ArticleIdentification", required = true)
        ArticleIdentification articleIdentification;

        @XmlElement(name = "RequestedQuantity", required = true)
        Quantity requestedQuantity;
    }

    /** {@code ew:OrderLine} of a quote. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class QuoteLine {

        @XmlElement(name = "LineID")
        String lineId;

        @XmlElement(name = "OrderedArticle")
        QuoteArticle orderedArticle;
    }

    /** {@code ew:OrderedArticle} of a quote line. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class QuoteArticle {

        @XmlElement(name = "ArticleIdentification")
        ArticleIdentification articleIdentification;

        /**
         * Four-valued availability code: {@code 0} could not be determined, {@code 1} available in
         * the requested quantity, {@code 2} partially available, {@code 3} not currently available.
         *
         * <p>{@code 3} is a statement and {@code 0} is a silence — the codec keeps them apart.
         */
        @XmlElement(name = "Availability")
        String availability;

        @XmlElement(name = "ArticleComment")
        String articleComment;

        @XmlElement(name = "RequestedQuantity")
        Quantity requestedQuantity;

        /** Line-level outcome; the code table distinguishes "not known to the supplier". */
        @XmlElement(name = "Error")
        ErrorBlock error;

        /** Quantities the vendor can deliver, against the dates it can deliver them. */
        @XmlElement(name = "ScheduleDetails")
        List<ScheduleDetail> scheduleDetails = new ArrayList<>();
    }

    /** {@code ew:ScheduleDetails} — one promised quantity at one date. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ScheduleDetail {

        @XmlElement(name = "DeliveryDate")
        String deliveryDate;

        /**
         * The quantity available at {@link #deliveryDate}.
         *
         * <p>The spec names this element {@code ConfirmedQuantity} on A2.5 while giving it the
         * {@code AvailableQuantity} schema. Both spellings are accepted rather than losing a
         * quantity to a naming inconsistency in the source document.
         */
        @XmlElement(name = "ConfirmedQuantity")
        Quantity confirmedQuantity;

        @XmlElement(name = "AvailableQuantity")
        Quantity availableQuantity;
    }

    /** {@code ew:ArticleIdentification} — the identifiers naming one article. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "articleIdentificationA2",
            propOrder = {"buyersArticleId", "manufacturersArticleId", "eanuccArticleId"})
    static class ArticleIdentification {

        @XmlElement(name = "BuyersArticleID")
        String buyersArticleId;

        @XmlElement(name = "ManufacturersArticleID")
        String manufacturersArticleId;

        /** 13-digit GTIN. */
        @XmlElement(name = "EANUCCArticleID")
        String eanuccArticleId;
    }

    /** {@code ew:BuyerParty} / {@code ew:Consignee}. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(
            name = "partyA2",
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

    /** A quantity element: one {@code QuantityValue} child. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "quantityA2")
    static class Quantity {

        @XmlElement(name = "QuantityValue", required = true)
        String quantityValue;

        static Quantity of(int value) {
            Quantity quantity = new Quantity();
            quantity.quantityValue = Integer.toString(value);
            return quantity;
        }
    }

    /** {@code ew:CustomerReference} — a single document identifier. */
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "documentReferenceA2")
    static class DocumentReference {

        @XmlElement(name = "DocumentID", required = true)
        String documentId;

        static DocumentReference of(String documentId) {
            DocumentReference reference = new DocumentReference();
            reference.documentId = documentId;
            return reference;
        }
    }

    /** {@code ew:ErrorHead} / {@code ew:Error}. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ErrorBlock {

        @XmlElement(name = "ErrorCode")
        String errorCode;

        @XmlElement(name = "ErrorText")
        String errorText;
    }
}
