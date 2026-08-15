/**
 * EDIWheel A2 XML adapter package (ADR-0051 §2): one package per wire-format family.
 *
 * <p>Covers the A2.5 stock inquiry norm from
 * {@code durion/docs/ediwheel/Swagger_UPX-Stock Inquiry PROD_1_2.yaml}, with the shared EDIWheel
 * error-code tables from {@code EDIWheel-XML_Order_Response_C1.pdf}. Everything vendor-shaped
 * lives here and nowhere else: the JAXB types model the documents literally, vendor element names
 * and all, and are package-private so a vendor type can never escape into the domain, an event
 * payload, or an API contract. Codecs convert to and from the canonical models in
 * {@code com.positivity.supplier.internal.domain.model} and perform no I/O (ADR-0051 §5).
 *
 * <h2>What A2.5 does and does not answer</h2>
 *
 * A2.5 answers <em>availability</em>: a four-valued code per line, plus a schedule of quantities
 * against delivery dates. It carries <strong>no price</strong> — the sibling C1.0 inquiry on the
 * same Michelin endpoint is the norm that adds {@code PriceDetails/NetUnitPrice}. Callers that
 * need a supplier price take it from the PRICAT price entries (CAP-318), which is the owner of
 * that fact; the quote fields on the canonical result stay null on this norm rather than being
 * filled with a number A2.5 never stated.
 *
 * <h2>Why a package-level namespace declaration</h2>
 *
 * The A2 norms put their documents in {@code http://www.reifen.net}, the same namespace as the C1
 * family, so outbound documents are emitted fully qualified. Inbound documents are normalised into
 * that namespace before unmarshalling rather than being required to arrive in it — see
 * {@code com.positivity.supplier.internal.adapter.ediwheelxml.EdiwheelXmlSupport}.
 */
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "http://www.reifen.net",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED,
        // Prefix pinned to ew: for the same reason the C1 package pins it — the norm's own examples
        // use it, and an inquiry a vendor parses is the wrong place to bet on every vendor's parser
        // being namespace-correct rather than prefix-matching.
        xmlns = {@jakarta.xml.bind.annotation.XmlNs(prefix = "ew", namespaceURI = "http://www.reifen.net")})
package com.positivity.supplier.internal.adapter.ediwheela2;
