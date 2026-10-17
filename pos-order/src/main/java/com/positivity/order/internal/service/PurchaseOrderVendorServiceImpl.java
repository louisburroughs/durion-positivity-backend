package com.positivity.order.internal.service;

import com.positivity.order.internal.dto.purchaseorder.ListPurchaseOrderVendorsRequest;
import com.positivity.order.internal.dto.purchaseorder.PurchaseOrderVendorResponse;
import com.positivity.order.internal.entity.ExtSupplierVendor;
import com.positivity.order.internal.enums.PurchaseOrderVendorStatus;
import com.positivity.order.internal.exception.PurchaseOrderRequestValidationException;
import com.positivity.order.internal.repository.ExtSupplierVendorRepository;
import com.positivity.web.common.ReplicationPendingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads {@code ext_supplier_vendor} for the purchase-order screens (CAP:550, #2680).
 *
 * <p>The copy is what {@link SupplierVendorGuard} checks, so a vendor this list shows as ACTIVE never answers 503
 * {@code VENDOR_REPLICATION_PENDING} on create, and a DRAFT order whose vendor is missing from the ACTIVE list is
 * exactly one approval will refuse. Row-level security scopes every query to the bound tenant (ADR-0062).
 *
 * <p>Sorting is closed to the four response fields: an unknown property would otherwise surface from Spring Data as a
 * {@code PropertyReferenceException} and a correlated 500, which is the caller's mistake, not the server's. Under
 * ADR-0072 the display name and the {@code q} filter are CONFIDENTIAL: this class logs neither.
 */
@Service
@RequiredArgsConstructor
public class PurchaseOrderVendorServiceImpl implements PurchaseOrderVendorService {

    static final String DEFAULT_SORT_PROPERTY = "displayName";
    static final String TIE_BREAKER_PROPERTY = "vendorId";

    /** The four response fields, so a client that echoes the page's own sort metadata is never refused. */
    static final Set<String> SORTABLE_PROPERTIES =
            Set.of("vendorNumber", DEFAULT_SORT_PROPERTY, "status", TIE_BREAKER_PROPERTY);

    private static final char LIKE_ESCAPE = '\\';

    private final ExtSupplierVendorRepository vendors;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<PurchaseOrderVendorResponse> listVendors(
            @NonNull ListPurchaseOrderVendorsRequest filter, @NonNull Pageable pageable) {
        return vendors.findAll(specification(filter), sortedAndTieBroken(pageable))
                .map(PurchaseOrderVendorServiceImpl::toResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PurchaseOrderVendorResponse getVendor(@NonNull UUID vendorId) {
        return vendors.findById(vendorId)
                .map(PurchaseOrderVendorServiceImpl::toResponse)
                .orElseThrow(() -> new ReplicationPendingException(
                        SupplierVendorGuard.VENDOR_REPLICATION_PENDING,
                        SupplierVendorGuard.VENDOR_REPLICATION_PENDING_MESSAGE,
                        vendorId));
    }

    private static @NonNull Specification<ExtSupplierVendor> specification(
            @NonNull ListPurchaseOrderVendorsRequest filter) {
        List<Specification<ExtSupplierVendor>> predicates = Stream.of(
                        statusIs(filter.getStatus()), numberOrNameContains(filter.getQ()))
                .filter(Objects::nonNull)
                .toList();
        return predicates.isEmpty() ? Specification.unrestricted() : Specification.allOf(predicates);
    }

    private static @Nullable Specification<ExtSupplierVendor> statusIs(@Nullable PurchaseOrderVendorStatus status) {
        if (status == null) {
            return null;
        }
        ExtSupplierVendor.Status stored = toEntity(status);
        return (root, query, cb) -> cb.equal(root.get("status"), stored);
    }

    private static @Nullable Specification<ExtSupplierVendor> numberOrNameContains(@Nullable String q) {
        String needle = q == null ? "" : q.trim();
        if (needle.isEmpty()) {
            return null;
        }
        String pattern = "%" + escapeLike(needle.toLowerCase(Locale.ROOT)) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("vendorNumber")), pattern, LIKE_ESCAPE),
                cb.like(cb.lower(root.get("displayName")), pattern, LIKE_ESCAPE));
    }

    /** Explicit in both directions: a value added to one enum and not the other is a compile error, not a 500. */
    static ExtSupplierVendor.@NonNull Status toEntity(@NonNull PurchaseOrderVendorStatus status) {
        return switch (status) {
            case ACTIVE -> ExtSupplierVendor.Status.ACTIVE;
            case INACTIVE -> ExtSupplierVendor.Status.INACTIVE;
        };
    }

    static @NonNull PurchaseOrderVendorStatus toResponse(ExtSupplierVendor.@NonNull Status status) {
        return switch (status) {
            case ACTIVE -> PurchaseOrderVendorStatus.ACTIVE;
            case INACTIVE -> PurchaseOrderVendorStatus.INACTIVE;
        };
    }

    /** A literal match: the caller's {@code %}, {@code _} and {@code \} are characters, not wildcards. */
    static @NonNull String escapeLike(@NonNull String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') {
                out.append(LIKE_ESCAPE);
            }
            out.append(c);
        }
        return out.toString();
    }

    /**
     * Keeps the caller's page and sort, refuses a sort outside {@link #SORTABLE_PROPERTIES}, defaults an unsorted
     * request to display name and appends the vendor id so equal names page deterministically.
     */
    static @NonNull Pageable sortedAndTieBroken(@NonNull Pageable pageable) {
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : pageable.getSort()) {
            if (!SORTABLE_PROPERTIES.contains(order.getProperty())) {
                throw new PurchaseOrderRequestValidationException("Unsupported sort property '"
                        + abbreviate(order.getProperty()) + "'; sort by vendorNumber, displayName, status or vendorId");
            }
            orders.add(order);
        }
        if (orders.isEmpty()) {
            orders.add(Sort.Order.asc(DEFAULT_SORT_PROPERTY));
        }
        if (orders.stream().noneMatch(order -> TIE_BREAKER_PROPERTY.equals(order.getProperty()))) {
            orders.add(Sort.Order.asc(TIE_BREAKER_PROPERTY));
        }
        return pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(orders))
                : Pageable.unpaged(Sort.by(orders));
    }

    /** The caller's sort token, cut to a readable length before it is echoed in the 400. */
    private static @NonNull String abbreviate(@NonNull String property) {
        return property.length() <= 64 ? property : property.substring(0, 64) + "…";
    }

    private static @NonNull PurchaseOrderVendorResponse toResponse(@NonNull ExtSupplierVendor vendor) {
        return new PurchaseOrderVendorResponse(
                vendor.getVendorId(),
                vendor.getVendorNumber(),
                vendor.getDisplayName(),
                toResponse(vendor.getStatus()),
                vendor.getPurchaseOrderSupplierRef() != null);
    }
}
