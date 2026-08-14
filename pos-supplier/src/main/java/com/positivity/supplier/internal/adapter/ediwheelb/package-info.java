/**
 * EDIWHEEL B-norm adapter package (ADR-0051 §2): one package per wire-format family.
 *
 * <p>Everything vendor-shaped lives here and nowhere else. The wire records in this package model
 * the EDIWheel documents literally — vendor field names, vendor date formats, vendor string-typed
 * amounts — and are deliberately package-private so a vendor type can never escape into the
 * domain, the service layer, an event payload, or an API contract. Codecs convert to and from the
 * canonical models in {@code com.positivity.supplier.internal.domain.model} and perform no I/O
 * (ADR-0051 §5).
 */
package com.positivity.supplier.internal.adapter.ediwheelb;
