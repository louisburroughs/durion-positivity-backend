/**
 * EDIWheel Invoice B3.3 wire types and codec (CAP-321 #1227).
 *
 * <h2>Why a package of its own</h2>
 *
 * The sibling {@code ediwheelb} package carries the JSON B-family codecs, which want no XML schema.
 * B3.3 is XML, and its documents live in {@code http://www.reifen.net} — the same namespace as every
 * other EDIWheel document. Declaring that at package level is what lets each element be written
 * once without a namespace on every annotation, and it pairs with the shared reader that rewrites
 * incoming documents into that namespace rather than requiring vendors to arrive in it.
 *
 * <p>The wire types are deliberately package-private and made of strings, including the amounts, so
 * a vendor type can never escape into the domain, an event payload or an API contract, and so no
 * decision about what an unreadable figure means gets taken here (ADR-0051 §5).
 */
@jakarta.xml.bind.annotation.XmlSchema(
        namespace = "http://www.reifen.net",
        elementFormDefault = jakarta.xml.bind.annotation.XmlNsForm.QUALIFIED)
package com.positivity.supplier.internal.adapter.ediwheelb3;
