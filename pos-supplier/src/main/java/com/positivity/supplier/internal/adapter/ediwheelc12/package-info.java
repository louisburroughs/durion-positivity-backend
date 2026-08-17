/**
 * EDIWheel C1.2 JSON adapter package (ADR-0051 §2) — the {@code ediwheel.net} MKCAT API.
 *
 * <h2>A third wire generation</h2>
 *
 * The A2.x norms are XML, the B-series are report-style JSON and XML, C1.x is the current XML
 * guideline. C1.2 is the resource-and-message JSON API that {@code ediwheel.net} publishes centrally
 * and that EDIWheel manufacturers are expected to converge on. It is not a vendor's own API: the
 * marketing catalogue is served by the standards body, so one integration covers every participating
 * manufacturer rather than one per vendor.
 *
 * <p>That is also why this package holds no per-vendor quirks. If a manufacturer's marketing data
 * needed special handling here, the value of a central catalogue would already have been lost.
 *
 * <h2>What C1.2 describes, and what it does not</h2>
 *
 * Tread <em>designs</em>, not articles. A design spans many sizes, each with its own EAN, and MKCAT
 * carries none of them — so nothing decoded here can be matched to a product by article code. The
 * identifiers available are brand, design names and the catalogue's own variant id. Whether they
 * resolve to anything is the consuming domain's problem, and one that no amount of care in this
 * package can solve.
 *
 * <p>Wire records are package-private so a catalogue type cannot escape into the domain, an event
 * payload or an API contract. Codecs convert to canonical models and perform no I/O (ADR-0051 §5).
 */
package com.positivity.supplier.internal.adapter.ediwheelc12;
