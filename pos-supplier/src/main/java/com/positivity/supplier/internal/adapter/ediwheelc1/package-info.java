/**
 * EDIWheel C1 XML adapter package (ADR-0051 §2): one package per wire-format family.
 *
 * <p>Covers the C1 message implementation guidelines — order creation C1.0 and order status
 * C1.1 — from {@code durion/docs/ediwheel/EDIWHEEL Order Creation PROD_2 (2).yaml} and
 * {@code EDIWHEEL Order Status API PROD_1.yaml}. Everything vendor-shaped lives here and nowhere
 * else: the JAXB types model the documents literally, vendor element names and all, and are
 * package-private so a vendor type can never escape into the domain, an event payload, or an API
 * contract. Codecs convert to and from the canonical models in
 * {@code com.positivity.supplier.internal.domain.model} and perform no I/O (ADR-0051 §5).
 *
 * <h2>Why a package-level namespace declaration</h2>
 *
 * The C1 norms put their documents in {@code http://www.reifen.net}, so outbound documents are
 * emitted fully qualified. Inbound documents are normalised into that namespace before
 * unmarshalling rather than being required to arrive in it — see
 * {@code C1XmlSupport}. Vendors differ on whether they qualify child elements, and a strict
 * reader would reject a document that is otherwise perfectly well formed for its market.
 */
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "http://www.reifen.net",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED,
        // The ew: prefix is pinned rather than left to the marshaller's default namespace. The two
        // are equivalent XML, but the norm's own examples use ew:, and a commercial document a
        // vendor may hold us to is the wrong place to bet on every vendor's parser being namespace
        // -correct rather than prefix-matching.
        xmlns = {@jakarta.xml.bind.annotation.XmlNs(prefix = "ew", namespaceURI = "http://www.reifen.net")})
package com.positivity.supplier.internal.adapter.ediwheelc1;
