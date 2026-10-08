package com.positivity.supplier.internal.vendor.service;

import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.entity.SupplierVendorTaxIdRevealEntity;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.entity.VendorTaxRegistration;
import com.positivity.supplier.internal.enums.TaxIdRevealOutcome;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.exception.TaxIdRevealReasonRejectedException;
import com.positivity.supplier.internal.exception.VendorTaxIdUnreadableException;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.supplier.internal.repository.SupplierVendorTaxIdRevealRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRecordView;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealRequest;
import com.positivity.supplier.internal.vendor.service.model.TaxIdRevealView;
import com.positivity.tenancy.TenantContext;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The reveal of a vendor's tax-registration number (#2621, Security ruling on #2617, ruling 4).
 *
 * <h2>Order, and why</h2>
 *
 * <ol>
 *   <li>Load the vendor and the registration (404 otherwise). Nothing has been decrypted.
 *   <li>Decrypt, catching only the unreadable case. A failure is a reveal that happened and produced nothing.
 *   <li>Refuse a reason that contains the number itself (Security confirmation on louisburroughs/durion#571):
 *       separators removed from both, compared case-insensitively. The refusal is 400 {@code VALIDATION_ERROR},
 *       reveals nothing, and records {@code REASON_REJECTED} with no reason, because that reason held the
 *       number. The reason is never logged.
 *   <li>Write the audit row through {@link VendorTaxIdRevealRecorder}, in this transaction, with no catch. If
 *       it fails, the exception propagates, the transaction rolls back, and the number never leaves this
 *       method.
 *   <li>Only then return the number, or rethrow the unreadable failure.
 * </ol>
 *
 * <p>{@code noRollbackFor} the unreadable and reason-rejected exceptions: each is a reveal that happened and
 * returned nothing, and its row must survive. An unreadable number is the attempt most worth
 * recording (a ciphertext copied between rows, a key retired without being carried into
 * {@code previous-keys}), and rolling back would delete that evidence. It is logged with the vendor,
 * registration and key id only.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VendorTaxIdRevealServiceImpl implements VendorTaxIdRevealService {

    /** Largest audit page, as the vendor list. */
    static final int MAX_PAGE_SIZE = 200;

    private final SupplierVendorRepository vendorRepository;
    private final SupplierVendorTaxIdRevealRepository revealRepository;
    private final VendorTaxIdRevealRecorder recorder;
    private final VendorTaxIdCipher cipher;

    @Override
    @NonNull
    @Transactional(noRollbackFor = {VendorTaxIdUnreadableException.class, TaxIdRevealReasonRejectedException.class})
    public TaxIdRevealView reveal(
            @NonNull UUID vendorId, @NonNull UUID registrationId, @NonNull TaxIdRevealRequest request) {
        Objects.requireNonNull(registrationId, "registrationId must not be null");
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        VendorTaxRegistration registration = vendor.getTaxRegistrations().stream()
                .filter(candidate -> candidate.registrationId().equals(registrationId))
                .findFirst()
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.VENDOR_TAX_REGISTRATION_NOT_FOUND,
                        "Tax registration " + registrationId + " does not exist on vendor " + vendorId));
        UUID tenantId = vendor.getTenantId() != null ? vendor.getTenantId() : TenantContext.require();

        String number = null;
        VendorTaxIdUnreadableException failure = null;
        try {
            number = cipher.open(tenantId, vendorId, registrationId, registration.numberCiphertext());
        } catch (VendorTaxIdUnreadableException ex) {
            failure = ex;
        }

        if (number != null && reasonCarries(request.reason(), number)) {
            number = null;
            // The row first, with no reason: that reason holds the number. Then the refusal, which keeps the row.
            recorder.record(vendorId, registration, null, TaxIdRevealOutcome.REASON_REJECTED);
            throw new TaxIdRevealReasonRejectedException();
        }

        // Before anything is returned, in this transaction, with no catch.
        recorder.record(
                vendorId,
                registration,
                Objects.requireNonNull(request.reason(), "reason"),
                failure == null ? TaxIdRevealOutcome.REVEALED : TaxIdRevealOutcome.UNREADABLE);

        if (failure != null) {
            // Vendor, registration and key id only: never ciphertext, never last4.
            log.error(
                    "Vendor tax-registration number is unreadable [{}]: vendor={} registration={} keyId={}."
                            + " Nothing was revealed; the attempt is recorded as UNREADABLE. An authentication"
                            + " failure may be a ciphertext moved between rows or a retired key.",
                    failure.getFailure(),
                    vendorId,
                    registrationId,
                    failure.getKeyId());
            throw failure;
        }
        log.info("Vendor tax registration {} of vendor {} revealed", registrationId, vendorId);
        return new TaxIdRevealView(
                registrationId, registration.scheme(), registration.region(), Objects.requireNonNull(number));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<TaxIdRevealRecordView> listReveals(@NonNull UUID vendorId, int page, int size) {
        if (page < 0) {
            throw invalid("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        loadVendor(vendorId);
        Page<SupplierVendorTaxIdRevealEntity> found =
                revealRepository.findByVendorIdOrderByRevealedAtDescRevealIdDesc(vendorId, PageRequest.of(page, size));
        return new PagedResponse<>(
                found.getContent().stream()
                        .map(VendorTaxIdRevealServiceImpl::toView)
                        .toList(),
                page,
                size,
                found.getTotalElements(),
                found.getTotalPages());
    }

    /** Whether {@code reason} contains {@code number}, separators removed from both, ignoring case. */
    static boolean reasonCarries(String reason, String number) {
        String bareNumber = alphanumerics(number);
        return !bareNumber.isEmpty() && alphanumerics(reason).contains(bareNumber);
    }

    private static String alphanumerics(String value) {
        return value.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    @NonNull
    private SupplierVendorEntity loadVendor(@NonNull UUID vendorId) {
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        return vendorRepository
                .findById(vendorId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.VENDOR_NOT_FOUND, "Vendor " + vendorId + " does not exist"));
    }

    private static TaxIdRevealRecordView toView(SupplierVendorTaxIdRevealEntity row) {
        List<String> roles = row.getRevealedByRoles().isEmpty()
                ? List.of()
                : Arrays.asList(row.getRevealedByRoles().split(","));
        return new TaxIdRevealRecordView(
                row.getRevealId(),
                row.getRegistrationId(),
                row.getScheme(),
                row.getRevealedBy(),
                List.copyOf(roles),
                row.getReason(),
                row.getCorrelationId(),
                row.getRevealedAt(),
                com.positivity.supplier.internal.vendor.service.model.TaxIdRevealOutcome.valueOf(
                        row.getOutcome().name()));
    }

    private static SupplierValidationException invalid(String message) {
        return new SupplierValidationException(SupplierValidationException.VALIDATION_ERROR, message);
    }
}
