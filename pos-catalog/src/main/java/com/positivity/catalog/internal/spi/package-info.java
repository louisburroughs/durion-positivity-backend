/**
 * Labor-time provider SPI (#1569 Phase 1, sourcing plan §3.3; pattern mirrors
 * {@code pos-supplier/internal/spi} per ADR-0058 §3).
 *
 * <p>Ports and their model records are the <em>Durion-normalized</em> provider contract: vendor
 * adapters under {@code internal.adapter} translate vendor reality onto these types, never the
 * reverse. This package is private plumbing — deliberately NOT a grant surface; other modules
 * reach labor times only through the granted {@code catalog.service} contract or events.
 */
package com.positivity.catalog.internal.spi;
