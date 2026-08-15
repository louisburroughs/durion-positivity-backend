package com.positivity.catalog.internal.client;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Client for the supplier live stock inquiry — the platform's single approved synchronous
 * cross-domain supplier read (ADR-0044 amendment 2026-08-10, CAP-319 #1225).
 *
 * <h2>This is the allowlisted edge, and it is deliberately narrow</h2>
 *
 * ADR-0044 R1 forbids synchronous domain-to-domain calls, and every other cross-domain read in this
 * module goes through an event-fed replica. The amendment permits exactly one exception, for exactly
 * one reason: vendor stock cannot be replicated. It is a fact that lives at the vendor, changes
 * without telling us, and is worthless the moment it is stale — a replica of it would be a
 * confidently wrong number on a customer's screen.
 *
 * <p>{@code DomainWallsTest} allowlists this file by name, not pos-catalog as a module. Any other
 * client here that reaches for pos-supplier still fails the build.
 *
 * <h2>Absence is not zero, and it never throws</h2>
 *
 * An empty {@link Optional} means "we could not find out" — the vendor was unreachable, the
 * capability is not configured, or the deployment has no supplier integration at all. It never means
 * "the vendor has none". The composition renders the difference.
 */
public interface SupplierStockClient {

    /**
     * Asks a vendor what it holds for one article at one receiving location.
     *
     * @param vendorProfileId the vendor profile to ask
     * @param deliveryLocationId the receiving location; availability is consignee-specific, so an
     *     answer for one location is never valid for another
     * @param articleEan EAN/GTIN of the article, when known
     * @param supplierArticleCode the vendor's own article code, when known
     * @return the answer, or empty when no answer could be obtained — never an exception, and never
     *     a fabricated zero
     */
    Optional<StockInquiryClientResponse> inquireStock(
            UUID vendorProfileId, UUID deliveryLocationId, String articleEan, String supplierArticleCode);

    /**
     * The vendor's answer about one inquiry.
     *
     * @param status document-level outcome as the supplier service reported it
     * @param lines per-article answers; empty unless the status is {@code OK}
     * @param asOf when the answer was obtained; a cached answer carries the age of the fact
     */
    record StockInquiryClientResponse(String status, List<StockInquiryClientLine> lines, Instant asOf) {}

    /**
     * One article's answer.
     *
     * @param status {@code AVAILABLE}, {@code UNAVAILABLE}, {@code NOT_LISTED} or
     *     {@code NOT_ANSWERED}
     * @param availableQuantity quantity the vendor can supply; null when it stated none, and zero
     *     only when the status is {@code UNAVAILABLE}
     * @param earliestDeliveryDate earliest date the vendor promised any quantity, when stated
     */
    record StockInquiryClientLine(String status, Integer availableQuantity, LocalDate earliestDeliveryDate) {}
}
