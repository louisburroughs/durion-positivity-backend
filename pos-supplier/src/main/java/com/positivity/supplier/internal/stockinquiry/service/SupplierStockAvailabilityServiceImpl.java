package com.positivity.supplier.internal.stockinquiry.service;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.domain.model.SupplierCapability;
import com.positivity.supplier.internal.domain.model.SupplierRef;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiry;
import com.positivity.supplier.internal.domain.model.SupplierStockInquiryResult;
import com.positivity.supplier.internal.entity.ExtProductCodeReplica;
import com.positivity.supplier.internal.entity.SupplierProfileEntity;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.ExtProductCodeReplicaRepository;
import com.positivity.supplier.internal.repository.SupplierEndpointBindingRepository;
import com.positivity.supplier.internal.repository.SupplierProfileRepository;
import com.positivity.supplier.internal.stockinquiry.service.model.StockAvailabilityView;
import com.positivity.supplier.service.model.StockInquiryResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Implements the product-keyed availability fan-out (#1637 decisions 1-3).
 *
 * <h2>Identity resolution never leaves this module</h2>
 *
 * The caller names a catalog product; the EAN/UPC that a vendor is actually asked about comes from
 * the local {@code ext_product_code} replica (ADR-0044 R1/R3 — no synchronous ask to pos-catalog,
 * for a SKU either: the replica keeps the SKU off the same {@code catalog.product.updated} fact).
 * The replica holds one typed code per product, {@code EAN} or {@code UPC}; whichever the product
 * carries is used, and both travel to the vendor as the GTIN-family article number A2.5 expects.
 * A product this module cannot name to a vendor is a 404, not a degraded 200: every vendor
 * answering {@code NOT_LISTED} would point an operator at vendor listings when the fix is in the
 * catalog.
 *
 * <h2>The deadline bounds the page, not the vendors</h2>
 *
 * Every enabled vendor is asked concurrently and the whole fan-out is given one budget
 * ({@code pos.supplier.stockinquiry.fanout-deadline}). A vendor that has not answered when it
 * expires is reported {@code SUPPLIER_UNAVAILABLE} — the same answer as any other silence — and
 * its in-flight call is cancelled. Without the ceiling, the read's latency would be the slowest
 * vendor's bad afternoon.
 *
 * <h2>Per-vendor {@code fetchedAt} is honest for cache hits</h2>
 *
 * A cached answer reports the instant of the original vendor call, not of the cache hit; the cache
 * carries it for exactly this. {@code asOf} is the vendor's stated observation time (the fetch
 * instant when the norm states none, as on A2.5), and staleness (#1637 decision 3) is judged from
 * {@code asOf} against the backend-owned threshold echoed in the response.
 */
@Slf4j
@Service
public class SupplierStockAvailabilityServiceImpl implements SupplierStockAvailabilityService {

    private final ExtProductCodeReplicaRepository replicaRepository;
    private final SupplierEndpointBindingRepository bindingRepository;
    private final SupplierProfileRepository profileRepository;
    private final StockInquiryRunner runner;
    private final StockInquiryCache cache;
    private final Clock clock;
    private final ExecutorService fanoutExecutor;
    private final Duration fanoutDeadline;
    private final Duration stalenessThreshold;

    public SupplierStockAvailabilityServiceImpl(
            ExtProductCodeReplicaRepository replicaRepository,
            SupplierEndpointBindingRepository bindingRepository,
            SupplierProfileRepository profileRepository,
            StockInquiryRunner runner,
            StockInquiryCache cache,
            Clock clock,
            @Qualifier("stockAvailabilityFanoutExecutor") ExecutorService fanoutExecutor,
            @Value("${pos.supplier.stockinquiry.fanout-deadline:PT10S}") Duration fanoutDeadline,
            @Value("${pos.supplier.availability.staleness-threshold:PT15M}") Duration stalenessThreshold) {
        this.replicaRepository = replicaRepository;
        this.bindingRepository = bindingRepository;
        this.profileRepository = profileRepository;
        this.runner = runner;
        this.cache = cache;
        this.clock = clock;
        this.fanoutExecutor = fanoutExecutor;
        this.fanoutDeadline = fanoutDeadline;
        this.stalenessThreshold = stalenessThreshold;
    }

    @Override
    @NonNull
    public StockAvailabilityView checkAvailability(
            @Nullable UUID productId, @Nullable String sku, @NonNull UUID deliveryLocationId, int quantity) {

        ExtProductCodeReplica product = resolveProduct(productId, sku);
        String articleCode = product.getCode();
        List<SupplierProfileEntity> vendors = enabledStockInquiryVendors();

        List<StockAvailabilityView.VendorAvailability> results =
                vendors.isEmpty() ? List.of() : fanOut(vendors, articleCode, deliveryLocationId, quantity);

        return new StockAvailabilityView(
                product.getProductId(), deliveryLocationId, quantity, stalenessThreshold.toString(), results);
    }

    /**
     * Resolves the caller's product identity to the replica row carrying a vendor-queryable code.
     *
     * <p>Exactly one identity must be named: naming both invites them to disagree, and the read
     * would have to silently pick which one it answered for.
     */
    private ExtProductCodeReplica resolveProduct(@Nullable UUID productId, @Nullable String sku) {
        boolean hasSku = sku != null && !sku.isBlank();
        if ((productId == null) == !hasSku) {
            throw new SupplierValidationException(
                    SupplierValidationException.AVAILABILITY_IDENTITY_INVALID,
                    "Exactly one of productId or sku must be provided",
                    List.of(
                            new ApiError.FieldError("productId", "exactly one of productId or sku must be provided"),
                            new ApiError.FieldError("sku", "exactly one of productId or sku must be provided")));
        }

        ExtProductCodeReplica row;
        if (productId != null) {
            row = replicaRepository.findById(productId).orElse(null);
            if (row == null) {
                throw notResolvable("product " + productId + " is not known to the supplier catalog replica");
            }
        } else {
            List<ExtProductCodeReplica> matches = replicaRepository.findBySku(sku.trim());
            if (matches.isEmpty()) {
                throw notResolvable("no replicated product carries SKU '" + sku.trim() + "'");
            }
            if (matches.size() > 1) {
                // pos-catalog's uniqueness makes this a replication defect; refused rather than
                // guessed, because an arbitrary pick would answer about the wrong article.
                throw new SupplierConflictException(
                        SupplierConflictException.PRODUCT_SKU_AMBIGUOUS,
                        "SKU '" + sku.trim() + "' ambiguously names " + matches.size() + " replicated products");
            }
            row = matches.getFirst();
        }

        if (row.getCode() == null || row.getCode().isBlank()) {
            throw notResolvable("product " + row.getProductId() + " carries no EAN/UPC code to inquire with");
        }
        return row;
    }

    private static SupplierNotFoundException notResolvable(String detail) {
        return new SupplierNotFoundException(SupplierNotFoundException.PRODUCT_CODES_NOT_FOUND, detail);
    }

    /**
     * The fan-out set: enabled STOCK_INQUIRY bindings, narrowed to enabled profiles. A binding can
     * outlive its profile being switched off, and a switched-off vendor must not be asked. Ordered
     * by display name so the same deployment always answers in the same shape.
     */
    private List<SupplierProfileEntity> enabledStockInquiryVendors() {
        Set<UUID> profileIds = new LinkedHashSet<>();
        bindingRepository
                .findByCapabilityAndEnabledTrue(SupplierCapability.STOCK_INQUIRY)
                .forEach(binding -> profileIds.add(binding.getVendorProfileId()));
        if (profileIds.isEmpty()) {
            return List.of();
        }
        return profileRepository.findAllById(profileIds).stream()
                .filter(SupplierProfileEntity::isEnabled)
                .sorted(Comparator.comparing(
                                SupplierProfileEntity::getDisplayName, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(SupplierProfileEntity::getVendorProfileId))
                .toList();
    }

    private List<StockAvailabilityView.VendorAvailability> fanOut(
            List<SupplierProfileEntity> vendors, String articleCode, UUID deliveryLocationId, int quantity) {

        List<Callable<StockAvailabilityView.VendorAvailability>> tasks = new ArrayList<>(vendors.size());
        for (SupplierProfileEntity vendor : vendors) {
            tasks.add(() -> queryOneVendor(vendor, articleCode, deliveryLocationId, quantity));
        }

        List<Future<StockAvailabilityView.VendorAvailability>> futures;
        try {
            futures = fanoutExecutor.invokeAll(tasks, fanoutDeadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            // The request thread itself was interrupted (shutdown). Every vendor becomes the same
            // honest answer a deadline expiry is: we could not find out.
            Thread.currentThread().interrupt();
            return vendors.stream().map(this::unanswered).toList();
        }

        List<StockAvailabilityView.VendorAvailability> results = new ArrayList<>(vendors.size());
        for (int i = 0; i < vendors.size(); i++) {
            SupplierProfileEntity vendor = vendors.get(i);
            try {
                results.add(futures.get(i).get());
            } catch (CancellationException e) {
                // The deadline expired before this vendor answered. Not an error: the read is
                // partial by contract, and a slow vendor is reported exactly like an unreachable
                // one (#1637 decision 1).
                log.debug(
                        "Vendor {} had not answered the availability fan-out within {}",
                        vendor.getVendorProfileId(),
                        fanoutDeadline);
                results.add(unanswered(vendor));
            } catch (ExecutionException e) {
                // The runner's contract is "never throws"; anything landing here is a defect worth
                // a log line, but still not worth failing the page over one vendor.
                log.warn(
                        "Availability fan-out task for vendor {} threw unexpectedly",
                        vendor.getVendorProfileId(),
                        e.getCause());
                results.add(unanswered(vendor));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                results.add(unanswered(vendor));
            }
        }
        return results;
    }

    /** One vendor's leg: cache first, then a live call whose answer is cached for the next page. */
    private StockAvailabilityView.VendorAvailability queryOneVendor(
            SupplierProfileEntity vendor, String articleCode, UUID deliveryLocationId, int quantity) {

        StockInquiryCache.Key key = new StockInquiryCache.Key(
                vendor.getVendorProfileId(), deliveryLocationId, ArticleKeys.of(articleCode, null));
        StockInquiryCache.Answer cached = cache.get(key);
        if (cached != null) {
            // Only answered lines are ever cached, so a hit is always an OK answer.
            return answered(
                    vendor, StockInquiryResponse.Status.OK, List.of(cached.line()), cached.fetchedAt(), cached.asOf());
        }

        SupplierStockInquiryResult result = runner.inquireAvailability(
                new SupplierRef(vendor.getSupplierRef()),
                deliveryLocationId,
                new SupplierStockInquiry(
                        UUIDv7Generator.generate(),
                        List.of(new SupplierStockInquiry.Line(articleCode, null, quantity))));

        if (result.status() != SupplierStockInquiryResult.Status.OK
                && result.status() != SupplierStockInquiryResult.Status.NOT_LISTED) {
            return silent(
                    vendor, StockInquiryResponse.Status.valueOf(result.status().name()));
        }

        Instant fetchedAt = Instant.now(clock);
        Instant asOf = result.asOf() == null ? fetchedAt : result.asOf();
        for (SupplierStockInquiryResult.Line line : result.lines()) {
            cache.put(
                    new StockInquiryCache.Key(
                            vendor.getVendorProfileId(),
                            deliveryLocationId,
                            ArticleKeys.of(line.articleEan(), line.supplierArticleCode())),
                    line,
                    fetchedAt,
                    asOf);
        }
        // The vendor-level verdict mirrors the per-vendor inquiry's: a document the codec already
        // judged NOT_LISTED stays that, and an OK document whose every line is NOT_LISTED becomes
        // one — a vendor that answered "I carry none of this" answered, but not usefully.
        boolean noneListed = result.status() == SupplierStockInquiryResult.Status.NOT_LISTED
                || (!result.lines().isEmpty()
                        && result.lines().stream()
                                .allMatch(line -> line.status() == SupplierStockInquiryResult.LineStatus.NOT_LISTED));
        return answered(
                vendor,
                noneListed ? StockInquiryResponse.Status.NOT_LISTED : StockInquiryResponse.Status.OK,
                result.lines(),
                fetchedAt,
                asOf);
    }

    /** A vendor that answered: its instants travel, its staleness is judged, its lines mapped. */
    private StockAvailabilityView.VendorAvailability answered(
            SupplierProfileEntity vendor,
            StockInquiryResponse.Status status,
            List<SupplierStockInquiryResult.Line> lines,
            Instant fetchedAt,
            Instant asOf) {

        return new StockAvailabilityView.VendorAvailability(
                vendor.getVendorProfileId(),
                displayNameOf(vendor),
                status,
                fetchedAt,
                asOf,
                asOf.isBefore(Instant.now(clock).minus(stalenessThreshold)),
                lines.stream()
                        .map(line -> new StockAvailabilityView.Line(
                                StockInquiryResponse.LineStatus.valueOf(
                                        line.status().name()),
                                line.availableQuantity(),
                                line.earliestDeliveryDate(),
                                line.quotedUnitPrice(),
                                line.currency()))
                        .toList());
    }

    /** A vendor that gave no answer: no lines, no instants, no staleness to judge. */
    private StockAvailabilityView.VendorAvailability silent(
            SupplierProfileEntity vendor, StockInquiryResponse.Status status) {
        return new StockAvailabilityView.VendorAvailability(
                vendor.getVendorProfileId(), displayNameOf(vendor), status, null, null, null, List.of());
    }

    private StockAvailabilityView.VendorAvailability unanswered(SupplierProfileEntity vendor) {
        return silent(vendor, StockInquiryResponse.Status.SUPPLIER_UNAVAILABLE);
    }

    private static String displayNameOf(SupplierProfileEntity vendor) {
        return vendor.getDisplayName() == null ? vendor.getSupplierRef() : vendor.getDisplayName();
    }
}
