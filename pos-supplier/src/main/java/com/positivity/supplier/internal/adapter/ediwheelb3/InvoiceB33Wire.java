package com.positivity.supplier.internal.adapter.ediwheelb3;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import java.util.List;

/**
 * Wire shape of EDIWheel Invoice B3.3 ({@code invoice_list}).
 *
 * <p>Deliberately a transcription of the norm rather than a model: every field is a {@code String},
 * including the amounts. The canonical {@link
 * com.positivity.supplier.internal.domain.model.SupplierInvoice} is where meaning is assigned, and
 * parsing a number here would decide — silently, in the wrong place — what an unparseable amount
 * means. An invoice is money owed, so an amount we cannot read must be visible as such rather than
 * quietly becoming zero.
 *
 * <p>Only the fields the canonical model needs are declared. JAXB ignores the rest, and the norm
 * carries a great deal this integration has no use for (attachments, environment register ids,
 * allowance and charge breakdowns).
 */
final class InvoiceB33Wire {

    private InvoiceB33Wire() {}

    /** {@code invoice_list}: the document the vendor returns for a date window. */
    @XmlRootElement(name = "invoice_list")
    @XmlAccessorType(XmlAccessType.FIELD)
    static class InvoiceList {

        @XmlElement(name = "DocumentID")
        String documentId;

        @XmlElement(name = "Variant")
        String variant;

        @XmlElement(name = "NumberOfMessages")
        String numberOfMessages;

        @XmlElement(name = "ErrorHead")
        ErrorHead errorHead;

        @XmlElement(name = "invoice")
        List<Invoice> invoices;
    }

    /** Document-level failure. Present instead of invoices when the vendor refuses the window. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class ErrorHead {

        @XmlElement(name = "ErrorCode")
        String errorCode;

        @XmlElement(name = "ErrorText")
        String errorText;
    }

    /** One vendor AR document: an invoice, a credit note, or a financial adjustment. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class Invoice {

        @XmlElement(name = "IssueDate")
        String issueDate;

        @XmlElement(name = "DocumentNumber")
        String documentNumber;

        /** 380 invoice, 381 credit note, 83 credit adjustment, 84 debit adjustment. */
        @XmlElement(name = "DocumentTypeCode")
        String documentTypeCode;

        @XmlElement(name = "DocumentFunctionCode")
        String documentFunctionCode;

        @XmlElement(name = "DocumentCurrency")
        DocumentCurrency documentCurrency;

        @XmlElement(name = "References")
        HeaderReferences references;

        @XmlElement(name = "LineLevel")
        List<Line> lineLevel;

        @XmlElement(name = "Summary")
        Summary summary;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class DocumentCurrency {

        @XmlElement(name = "CurrencyCode")
        String currencyCode;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class HeaderReferences {

        @XmlElement(name = "CustomerReference")
        Reference customerReference;

        @XmlElement(name = "OrderReference")
        Reference orderReference;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Reference {

        @XmlElement(name = "ReferenceID")
        String referenceId;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Line {

        @XmlElement(name = "LineID")
        String lineId;

        @XmlElement(name = "References")
        HeaderReferences references;

        @XmlElement(name = "LineItemNetAmount")
        AmountType lineItemNetAmount;

        @XmlElement(name = "Article")
        Article article;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class Article {

        @XmlElement(name = "ArticleIdentification")
        ArticleIdentification articleIdentification;

        @XmlElement(name = "InvoicedQuantity")
        QuantityType invoicedQuantity;

        @XmlElement(name = "PriceDetails")
        PriceDetails priceDetails;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class ArticleIdentification {

        @XmlElement(name = "EANUCCArticleID")
        String eanuccArticleId;

        @XmlElement(name = "ManufacturersArticleID")
        String manufacturersArticleId;

        @XmlElement(name = "BuyersArticleID")
        String buyersArticleId;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class QuantityType {

        @XmlElement(name = "QuantityValue")
        String quantityValue;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class PriceDetails {

        @XmlElement(name = "PriceAmount")
        String priceAmount;
    }

    @XmlAccessorType(XmlAccessType.FIELD)
    static class AmountType {

        @XmlElement(name = "AmountValue")
        String amountValue;
    }

    /** Document totals as the vendor states them; never recomputed from the lines. */
    @XmlAccessorType(XmlAccessType.FIELD)
    static class Summary {

        @XmlElement(name = "TotalAmount")
        AmountType totalAmount;

        @XmlElement(name = "TaxableAmount")
        AmountType taxableAmount;

        @XmlElement(name = "TaxAmount")
        AmountType taxAmount;

        @XmlElement(name = "TotalLineItemsAmount")
        AmountType totalLineItemsAmount;
    }
}
