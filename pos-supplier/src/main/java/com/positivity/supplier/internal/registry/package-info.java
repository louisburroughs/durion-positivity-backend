/**
 * Adapter registry and resolution (ADR-0051 §3): codecs register against the triple
 * {@code (capability, protocolFamily, version)}; the vendor profile's binding names the
 * triple to use, so switching a vendor's norm version is a configuration change, never code.
 */
package com.positivity.supplier.internal.registry;
