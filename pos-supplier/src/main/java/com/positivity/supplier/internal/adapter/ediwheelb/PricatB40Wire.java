package com.positivity.supplier.internal.adapter.ediwheelb;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * Literal shape of an EDIWheel PRICAT B4.0 document
 * ({@code durion/docs/ediwheel/EDIWheel Price Catalog PROD_0.yaml}).
 *
 * <p>Package-private by design (ADR-0051 §2): these are vendor types, and the only thing allowed to
 * see them is the codec in this package. Every field is a {@code String} because the vendor sends
 * amounts, dates and flags as strings — parsing them here, at the boundary, is what keeps a
 * malformed vendor value from becoming a malformed canonical value.
 *
 * <p>Unknown properties are ignored: a B4.0 document carries roughly eighty article fields, this
 * codec consumes the pricing-relevant subset, and a vendor adding a field must not fail an import.
 */
final class PricatB40Wire {

    private PricatB40Wire() {}

    /** Root document: envelope header plus article lines. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Catalog(EnvelopeHeader envelopeHeader, List<Article> articles) {}

    /**
     * Document-level header. {@code date} is {@code yyyyMMddHHmm} in the vendor's examples but is
     * tolerated at date precision too; {@code errorCode} is {@code "0"} on a good document.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record EnvelopeHeader(
            String documentId,
            String date,
            String receiverId,
            String currency,
            String countryCode,
            String ediwheelVersion,
            String errorCode) {}

    /**
     * One article line. Only the identity, pricing, tax and effective-date fields are modelled;
     * the descriptive tyre attributes belong to MKCAT enrichment (CAP-318 story #1230), not to a
     * price catalog.
     *
     * @param pos                 1-based line position
     * @param ean                 EAN identity of the product
     * @param supplierCode        vendor's own article code — an alias, never an identifier
     * @param xReferenceCode      UPC in an EAN market, EAN in a UPC market
     * @param suggestedPrice      suggested retail price, as a string
     * @param grossPrice          gross price, as a string
     * @param grossPriceValidFrom {@code yyyyMMdd} validity start of the gross price
     * @param netValue            net price, as a string
     * @param netValueValidFrom   {@code yyyyMMdd} validity start of the net price
     * @param tax                 tax rate, as a string
     * @param taxId               reduced-tax-rate identifier, when the vendor applies one
     * @param recyclingFee        recycling fee amount, as a string
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record Article(
            Integer pos,
            String ean,
            String supplierCode,
            String xReferenceCode,
            String suggestedPrice,
            String grossPrice,
            String grossPriceValidFrom,
            String netValue,
            String netValueValidFrom,
            String tax,
            String taxId,
            String recyclingFee) {}
}
