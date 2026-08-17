package com.positivity.supplier.internal.stockinquiry.service;

import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.service.SupplierStockService;
import com.positivity.supplier.service.model.StockInquiryRequest;
import com.positivity.supplier.service.model.StockInquiryResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * The platform's single approved synchronous supplier read (ADR-0044 amendment 2026-08-10,
 * ADR-0049 §4), implemented for CAP-319 (#1225).
 *
 * <h2>It answers; it does not fail</h2>
 *
 * Callers are a product page and a procurement screen, both of which have something useful to show
 * without live vendor stock and nothing useful to show if this call throws. Every failure is
 * therefore a status. The only exceptions that escape are the ones that mean the caller passed
 * nonsense — a null request — because those are defects in the caller, not conditions to render.
 *
 * <h2>Cache reads are per article, not per inquiry</h2>
 *
 * A product page asks about one article many times; a procurement screen asks about several at
 * once, overlapping with what the page already asked. Caching whole inquiries would miss both
 * patterns, so the cache is keyed per article and an inquiry asks the vendor only about the articles
 * it does not already have an answer for. When every article is a hit, no call is made at all.
 *
 * <p>A cached answer keeps the {@code asOf} of the original inquiry rather than the moment it was
 * served. A caller deciding whether an availability is fresh enough to promise a customer needs the
 * age of the vendor's statement, and stamping "now" on a cache hit would report a minute-old fact as
 * current.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SupplierStockServiceImpl implements SupplierStockService {

    private final SupplierProfileRepository profileRepository;
    private final StockInquiryRunner runner;
    private final StockInquiryCache cache;
    private final Clock clock;

    @Override
    @NonNull
    public StockInquiryResponse inquireLiveStock(@NonNull StockInquiryRequest request) {
        Optional<SupplierProfileEntity> profile = profileRepository.findById(request.vendorProfileId());
        if (profile.isEmpty()) {
            // The caller named a vendor this deployment does not have. That is a configuration
            // problem where the caller lives, not a vendor being unreachable, and saying so is what
            // stops an operator waiting for a supplier to come back.
            log.warn("Stock inquiry names unknown vendor profile {}", request.vendorProfileId());
            return failed(request, StockInquiryResponse.Status.CONFIGURATION_ERROR);
        }

        Map<String, StockInquiryRequest.Line> byKey = new LinkedHashMap<>();
        for (StockInquiryRequest.Line line : request.lines()) {
            byKey.put(articleKey(line.articleEan(), line.supplierArticleCode()), line);
        }

        Map<String, StockInquiryCache.Answer> hits = new LinkedHashMap<>();
        List<StockInquiryRequest.Line> misses = new ArrayList<>();
        for (Map.Entry<String, StockInquiryRequest.Line> entry : byKey.entrySet()) {
            StockInquiryCache.Answer cached = cache.get(
                    new StockInquiryCache.Key(request.vendorProfileId(), request.deliveryLocationId(), entry.getKey()));
            if (cached == null) {
                misses.add(entry.getValue());
            } else {
                hits.put(entry.getKey(), cached);
            }
        }

        if (misses.isEmpty()) {
            return served(request, hits, List.of());
        }

        SupplierRef supplierRef = new SupplierRef(profile.get().getSupplierRef());
        SupplierStockInquiry inquiry = new SupplierStockInquiry(
                request.inquiryId(),
                misses.stream()
                        .map(line -> new SupplierStockInquiry.Line(
                                line.articleEan(), line.supplierArticleCode(), line.requestedQuantity()))
                        .toList());

        SupplierStockInquiryResult result =
                runner.inquireAvailability(supplierRef, request.deliveryLocationId(), inquiry);
        if (result.status() != SupplierStockInquiryResult.Status.OK
                && result.status() != SupplierStockInquiryResult.Status.NOT_LISTED) {
            // The vendor did not answer. Cached articles are not reported alongside it: a mixed
            // answer would look like a partial success, and a caller cannot tell which half of it to
            // trust. One inquiry, one verdict.
            return failed(request, map(result.status()));
        }

        Instant asOf = Instant.now(clock);
        for (SupplierStockInquiryResult.Line line : result.lines()) {
            cache.put(
                    new StockInquiryCache.Key(
                            request.vendorProfileId(),
                            request.deliveryLocationId(),
                            articleKey(line.articleEan(), line.supplierArticleCode())),
                    line,
                    asOf);
        }
        return served(request, hits, result.lines());
    }

    /**
     * Builds the answer from cached lines plus freshly inquired ones.
     *
     * <p>{@code asOf} on the document is the oldest fact in it. A caller asking "how current is
     * this?" is really asking "what is the worst case here?", and reporting the newest would let one
     * fresh line vouch for a set that is mostly older.
     */
    private StockInquiryResponse served(
            StockInquiryRequest request,
            Map<String, StockInquiryCache.Answer> hits,
            List<SupplierStockInquiryResult.Line> fresh) {

        Instant now = Instant.now(clock);
        List<StockInquiryResponse.Line> lines = new ArrayList<>();
        Instant oldest = null;

        for (StockInquiryCache.Answer answer : hits.values()) {
            lines.add(toResponseLine(answer.line()));
            oldest = older(oldest, answer.asOf());
        }
        for (SupplierStockInquiryResult.Line line : fresh) {
            lines.add(toResponseLine(line));
            oldest = older(oldest, now);
        }

        boolean noneListed = !lines.isEmpty()
                && lines.stream().allMatch(line -> line.status() == StockInquiryResponse.LineStatus.NOT_LISTED);
        StockInquiryResponse.Status status =
                noneListed ? StockInquiryResponse.Status.NOT_LISTED : StockInquiryResponse.Status.OK;
        return new StockInquiryResponse(request.inquiryId(), status, lines, oldest == null ? now : oldest);
    }

    private static Instant older(@Nullable Instant current, Instant candidate) {
        return current == null || candidate.isBefore(current) ? candidate : current;
    }

    private static StockInquiryResponse.Line toResponseLine(SupplierStockInquiryResult.Line line) {
        return new StockInquiryResponse.Line(
                line.articleEan(),
                line.supplierArticleCode(),
                StockInquiryResponse.LineStatus.valueOf(line.status().name()),
                line.availableQuantity(),
                line.earliestDeliveryDate(),
                line.quotedUnitPrice(),
                line.currency());
    }

    private static StockInquiryResponse.Status map(SupplierStockInquiryResult.Status status) {
        return StockInquiryResponse.Status.valueOf(status.name());
    }

    /** A failed inquiry carries no lines and no asOf: there is no fact to date. */
    private static StockInquiryResponse failed(StockInquiryRequest request, StockInquiryResponse.Status status) {
        return new StockInquiryResponse(request.inquiryId(), status, List.of(), null);
    }

    /**
     * The identity an answer is remembered and matched by.
     *
     * <p>Both identifiers participate: a vendor may echo either, and keying on one alone would let
     * an answer about an article identified by EAN be served for a request that named a supplier
     * code, which are not guaranteed to be the same article.
     */
    private static String articleKey(@Nullable String articleEan, @Nullable String supplierArticleCode) {
        return normalise(articleEan) + "|" + normalise(supplierArticleCode);
    }

    private static String normalise(@Nullable String value) {
        return value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
