package com.positivity.order.internal.client;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Asks a vendor what it can actually supply, for the lines a buyer is deciding about
 * (CAP-319 #1329, ADR-0044 amendment 2026-08-10).
 *
 * <h2>The one synchronous read out of this module</h2>
 *
 * Availability is worth nothing a minute old — a buyer choosing quantities needs what the vendor
 * says now, not what it said when a fact was last published. That is why the amendment carves this
 * one call out of the event-only rule, and why the wall grants it <em>by file name</em>: a second
 * client reaching pos-supplier still fails the build, and has to argue its own case.
 *
 * <h2>Never throws, always answers</h2>
 *
 * A supplier that is down must not fail a procurement screen. Every outcome — including a refused
 * connection — comes back as a status, so the caller decides what to render rather than catching an
 * exception whose only handler would be "carry on".
 */
public interface SupplierStockClient {

    /**
     * One inquiry covering every line asked about.
     *
     * <p>Batched deliberately: a ten-line procurement screen must not make ten vendor round trips,
     * and a vendor rate-limiting us because of it would degrade the whole screen rather than one row.
     *
     * @param vendorProfileId   the vendor to ask
     * @param deliveryLocationId where the goods would be delivered — required, because availability
     *     is consignee-specific and a default would answer a different question than the one asked
     * @param lines             the articles to ask about
     */
    @NonNull
    StockAnswer inquire(
            @NonNull UUID vendorProfileId, @NonNull UUID deliveryLocationId, @NonNull List<InquiryLine> lines);

    /** One article to ask about, named however the vendor knows it. */
    record InquiryLine(
            @Nullable String articleEan, @Nullable String supplierArticleCode, int requestedQuantity) {}

    /**
     * What came back.
     *
     * <p>{@code status} is never inferred from an empty line list: a vendor that answered "I have
     * none of these" and a vendor that could not be reached both produce no usable quantities, and
     * telling them apart is the entire point.
     *
     * @param asOf when the answer was obtained; null when there is no answer to date
     */
    record StockAnswer(
            @NonNull Status status,
            @NonNull List<AnswerLine> lines,
            @Nullable Instant asOf) {

        /** Whether anything here can be shown to a buyer as fact. */
        public boolean answered() {
            return status == Status.OK;
        }
    }

    /** Document-level outcome, mirroring the supplier contract so nothing is lost in translation. */
    enum Status {
        /** The vendor answered. Per-article outcomes are on the lines. */
        OK,
        /** The vendor could not be reached, failed, or its circuit breaker is open. */
        SUPPLIER_UNAVAILABLE,
        /** The vendor lists none of the inquired articles. */
        NOT_LISTED,
        /** No stock-inquiry binding on the vendor profile. */
        CAPABILITY_NOT_CONFIGURED,
        /** The vendor profile is configured but not usably so. */
        CONFIGURATION_ERROR
    }

    /** What the vendor said about one article. */
    enum LineStatus {
        /** The vendor has some, and said how many. */
        AVAILABLE,
        /** The vendor stated it has none. The only outcome that means "out of stock". */
        UNAVAILABLE,
        /** The vendor does not carry this article. */
        NOT_LISTED,
        /** The vendor said nothing about it. Not the same as having none. */
        NOT_ANSWERED
    }

    /**
     * One article's answer.
     *
     * @param availableQuantity null when the vendor stated no quantity; zero only when it stated it
     *     has none. Collapsing the two is how a buyer ends up told an article is unavailable when
     *     the vendor simply did not mention it.
     */
    record AnswerLine(
            @Nullable String articleEan,
            @Nullable String supplierArticleCode,
            @NonNull LineStatus status,
            @Nullable Integer availableQuantity,
            @Nullable LocalDate earliestDeliveryDate) {}
}
