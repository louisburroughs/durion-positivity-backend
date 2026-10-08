package com.positivity.supplier.internal.vendor.service;

import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.entity.SupplierVendorRemitChangeEntity;
import com.positivity.supplier.internal.entity.VendorRemitTo;
import com.positivity.supplier.internal.entity.VendorTaxIdCipher;
import com.positivity.supplier.internal.entity.VendorTaxRegistration;
import com.positivity.supplier.internal.exception.SupplierConflictException;
import com.positivity.supplier.internal.exception.SupplierForbiddenException;
import com.positivity.supplier.internal.exception.SupplierNotFoundException;
import com.positivity.supplier.internal.exception.SupplierValidationException;
import com.positivity.supplier.internal.repository.SupplierVendorRemitChangeRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.supplier.internal.service.model.PagedResponse;
import com.positivity.supplier.internal.vendor.VendorTaxRegistrationShapes;
import com.positivity.supplier.internal.vendor.service.model.RemitApprovalRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeStatus;
import com.positivity.supplier.internal.vendor.service.model.RemitChangeView;
import com.positivity.supplier.internal.vendor.service.model.RemitRejectionRequest;
import com.positivity.supplier.internal.vendor.service.model.RemitToDto;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationDto;
import com.positivity.supplier.internal.vendor.service.model.TaxRegistrationView;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorFactReplayResult;
import com.positivity.supplier.internal.vendor.service.model.VendorStatus;
import com.positivity.supplier.internal.vendor.service.model.VendorStatusChangeRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorUpdateRequest;
import com.positivity.supplier.internal.vendor.service.model.VendorView;
import com.positivity.tenancy.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The vendor master (#2516, ADR-0070 Decision 2, SPEC §4.9 "Vendors (AW23)").
 *
 * <h2>Remit-to changes need a second person</h2>
 *
 * A remit-to given at creation is stored as version 1 without approval — the first-bill rule in
 * pos-accounting (S24) is the control for a new vendor. Every later remit-to, including a first one
 * on a vendor created without, is a {@code PENDING} change request that is applied only when a holder
 * of {@code supplier:vendor_remit:approve} <em>who is not the requester</em> approves it. No tenant
 * setting relaxes that. A pending change is never on the vendor row and never published, so where the
 * shop's money goes cannot move on one person's word.
 *
 * <h2>Facts</h2>
 *
 * Every committed create, update, status change and approval queues {@code supplier.vendor.updated}
 * in the same transaction ({@link VendorFactPublisher}); the vendor is flushed first so the fact
 * carries the version the change produced.
 *
 * <h2>Tax registrations are sealed and masked (#2621)</h2>
 *
 * Every registration number is RESTRICTED (Security ruling on #2617, ruling 1). A number is sealed by
 * {@link VendorTaxIdCipher} for its tenant, vendor and registration the moment it arrives, next to its
 * {@code last4}; reads return {@link TaxRegistrationView} and never decrypt. An update keeps a stored
 * registration by its {@code registrationId} without the number, and compares ids and stored values, never
 * ciphertext. No message, field error or log line here ever holds a submitted number.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class SupplierVendorServiceImpl implements SupplierVendorService {

    /** Largest list page. */
    static final int MAX_PAGE_SIZE = 200;

    /** Largest replay page, as pos-customer's party-fact replay. */
    static final int MAX_REPLAY_LIMIT = 1000;

    private static final String VENDOR = "Vendor ";

    private final SupplierVendorRepository vendorRepository;
    private final SupplierVendorRemitChangeRepository remitChangeRepository;
    private final VendorNumberAllocator numberAllocator;
    private final VendorFactPublisher factPublisher;
    private final VendorTaxIdCipher taxIdCipher;
    private final Clock clock;

    // ── Reads ───────────────────────────────────────────────────────────────────────

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public PagedResponse<VendorView> listVendors(
            @Nullable String q, @Nullable VendorStatus status, int page, int size) {
        if (page < 0) {
            throw invalid("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw invalid("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String pattern = q == null || q.isBlank() ? null : "%" + q.strip().toLowerCase(Locale.ROOT) + "%";
        Page<SupplierVendorEntity> found = vendorRepository.search(
                pattern,
                status == null ? null : com.positivity.supplier.internal.enums.VendorStatus.valueOf(status.name()),
                PageRequest.of(page, size));
        return new PagedResponse<>(
                found.getContent().stream()
                        .map(SupplierVendorServiceImpl::toView)
                        .toList(),
                page,
                size,
                found.getTotalElements(),
                found.getTotalPages());
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public VendorView getVendor(@NonNull UUID vendorId) {
        return toView(loadVendor(vendorId));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<RemitChangeView> listRemitChanges(@NonNull UUID vendorId, @Nullable RemitChangeStatus status) {
        loadVendor(vendorId);
        List<SupplierVendorRemitChangeEntity> changes = status == null
                ? remitChangeRepository.findByVendorIdOrderByRequestedAtDesc(vendorId)
                : remitChangeRepository.findByVendorIdAndStatusOrderByRequestedAtDesc(
                        vendorId, com.positivity.supplier.internal.enums.RemitChangeStatus.valueOf(status.name()));
        return changes.stream().map(SupplierVendorServiceImpl::toView).toList();
    }

    // ── Vendor commands ─────────────────────────────────────────────────────────────

    @Override
    @NonNull
    public VendorView createVendor(@NonNull VendorCreateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        String actor = currentActor();
        Instant now = Instant.now(clock);
        String vendorNumber = request.vendorNumber();
        if (vendorNumber == null) {
            vendorNumber = numberAllocator.allocate();
        } else if (vendorRepository.existsByVendorNumber(vendorNumber)) {
            throw numberTaken(vendorNumber);
        }

        // The id is minted here, not at flush, because each registration number is sealed for its vendor
        // (the AAD binds tenant, vendor and registration). UUIDv7HibernateGenerator keeps an assigned id.
        UUID vendorId = UUIDv7Generator.generate();
        List<VendorTaxRegistration> registrations =
                resolveTaxRegistrations(TenantContext.require(), vendorId, List.of(), request.taxRegistrations());
        SupplierVendorEntity vendor = SupplierVendorEntity.builder()
                .vendorId(vendorId)
                .vendorNumber(vendorNumber)
                .legalName(request.legalName())
                .displayName(request.displayName())
                .taxRegistrations(new ArrayList<>(registrations))
                .defaultPaymentTerms(request.defaultPaymentTerms())
                .defaultCurrency(request.defaultCurrency())
                .status(com.positivity.supplier.internal.enums.VendorStatus.ACTIVE)
                .build();
        if (request.remitTo() != null) {
            // Given at creation: version 1 without approval (SPEC §4.9). The requester of record is the
            // creator, and nobody approved it — the fact says so with a null approver.
            vendor.setRemitTo(toEntity(request.remitTo()));
            vendor.setRemitToVersion(1);
            vendor.setRemitToChangedAt(now);
            vendor.setRemitToRequestedBy(actor);
        }
        try {
            vendor = vendorRepository.saveAndFlush(vendor);
        } catch (DataIntegrityViolationException e) {
            if (isVendorNumberTaken(e)) {
                // A concurrent create took the number between the check and the insert.
                throw numberTaken(vendorNumber);
            }
            // Any other integrity failure is a defect, not a taken number: surface it as one.
            throw e;
        }
        factPublisher.publish(vendor, now, actor);
        log.info("Created vendor {} ({})", vendor.getVendorNumber(), vendor.getVendorId());
        return toView(vendor);
    }

    @Override
    @NonNull
    public VendorView updateVendor(@NonNull UUID vendorId, @NonNull VendorUpdateRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        if (!Objects.equals(vendor.getVersion(), request.version())) {
            throw new SupplierConflictException(
                    SupplierConflictException.CONFLICT,
                    VENDOR + vendor.getVendorNumber() + " was changed by someone else (version "
                            + vendor.getVersion() + ", request carried " + request.version()
                            + "). Reload it and try again.");
        }
        // Resolved before anything is set: a refused registration writes nothing.
        UUID tenantId = vendor.getTenantId() != null ? vendor.getTenantId() : TenantContext.require();
        List<VendorTaxRegistration> registrations = resolveTaxRegistrations(
                tenantId, vendor.getVendorId(), vendor.getTaxRegistrations(), request.taxRegistrations());
        vendor.setLegalName(request.legalName());
        vendor.setDisplayName(request.displayName());
        boolean valueSupplied = request.taxRegistrations().stream().anyMatch(entry -> entry.number() != null);
        replaceTaxRegistrations(vendor, registrations, valueSupplied);
        vendor.setDefaultPaymentTerms(request.defaultPaymentTerms());
        vendor.setDefaultCurrency(request.defaultCurrency());
        Long versionBefore = vendor.getVersion();
        SupplierVendorEntity saved = vendorRepository.saveAndFlush(vendor);
        if (Objects.equals(saved.getVersion(), versionBefore)) {
            // Nothing changed, so there is no new state to publish: a fact here would repeat the last one
            // under the same aggregateVersion.
            return toView(saved);
        }
        factPublisher.publish(saved, Instant.now(clock), currentActor());
        return toView(saved);
    }

    @Override
    @NonNull
    public VendorView deactivateVendor(@NonNull UUID vendorId, @NonNull VendorStatusChangeRequest request) {
        return changeStatus(vendorId, request, com.positivity.supplier.internal.enums.VendorStatus.INACTIVE);
    }

    @Override
    @NonNull
    public VendorView reactivateVendor(@NonNull UUID vendorId, @NonNull VendorStatusChangeRequest request) {
        return changeStatus(vendorId, request, com.positivity.supplier.internal.enums.VendorStatus.ACTIVE);
    }

    /**
     * Deactivation does not touch the vendor's profiles: an inactive vendor's EDI documents still
     * arrive, and accounting records them as exceptions (S24).
     */
    private VendorView changeStatus(
            UUID vendorId,
            VendorStatusChangeRequest request,
            com.positivity.supplier.internal.enums.VendorStatus target) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        if (vendor.getStatus() == target) {
            throw new SupplierConflictException(
                    SupplierConflictException.CONFLICT,
                    VENDOR + vendor.getVendorNumber() + " is already " + target + ".");
        }
        vendor.setStatus(target);
        vendor.setStatusChangedAt(Instant.now(clock));
        vendor.setStatusReason(request.reason());
        VendorView view = commitAndPublish(vendor);
        log.info("Vendor {} is now {}", vendor.getVendorNumber(), target);
        return view;
    }

    // ── Remit-to changes ────────────────────────────────────────────────────────────

    @Override
    @NonNull
    public RemitChangeView requestRemitChange(@NonNull UUID vendorId, @NonNull RemitChangeRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        if (remitChangeRepository.existsByVendorIdAndStatus(
                vendorId, com.positivity.supplier.internal.enums.RemitChangeStatus.PENDING)) {
            throw changePending(vendor);
        }
        SupplierVendorRemitChangeEntity change = SupplierVendorRemitChangeEntity.builder()
                .vendorId(vendorId)
                .proposedRemitTo(toEntity(request.remitTo()))
                .reason(request.reason())
                .status(com.positivity.supplier.internal.enums.RemitChangeStatus.PENDING)
                .fromVersion(vendor.getRemitToVersion())
                .requestedBy(currentActor())
                .requestedAt(Instant.now(clock))
                .build();
        try {
            change = remitChangeRepository.saveAndFlush(change);
        } catch (DataIntegrityViolationException e) {
            // The one-pending partial unique index: a concurrent request won.
            throw changePending(vendor);
        }
        // Deliberately no fact: a pending change is invisible outside pos-supplier until approved.
        log.info(
                "Remit-to change {} requested for vendor {} (remit-to version {})",
                change.getChangeId(),
                vendor.getVendorNumber(),
                vendor.getRemitToVersion());
        return toView(change);
    }

    @Override
    @NonNull
    public RemitChangeView approveRemitChange(
            @NonNull UUID vendorId, @NonNull UUID changeId, @NonNull RemitApprovalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        SupplierVendorRemitChangeEntity change = loadPendingChange(vendorId, changeId);
        String approver = currentActor();
        if (approver.equals(change.getRequestedBy())) {
            throw new SupplierForbiddenException(
                    SupplierForbiddenException.VENDOR_REMIT_SELF_APPROVAL,
                    "A remit-to change must be approved by someone other than the person who requested it.");
        }
        Instant now = Instant.now(clock);
        int fromVersion = vendor.getRemitToVersion();
        int toVersion = fromVersion + 1;
        vendor.setRemitTo(change.getProposedRemitTo());
        vendor.setRemitToVersion(toVersion);
        vendor.setRemitToChangedAt(now);
        vendor.setRemitToRequestedBy(change.getRequestedBy());
        vendor.setRemitToApprovedBy(approver);

        change.setStatus(com.positivity.supplier.internal.enums.RemitChangeStatus.APPROVED);
        change.setToVersion(toVersion);
        change.setDecidedBy(approver);
        change.setDecidedAt(now);
        change.setDecisionNote(request.verificationNote());
        remitChangeRepository.save(change);

        commitAndPublish(vendor);
        // Vendor number and versions only — never the address.
        log.info(
                "Remit-to change {} approved for vendor {}: remit-to version {} -> {}",
                changeId,
                vendor.getVendorNumber(),
                fromVersion,
                toVersion);
        return toView(change);
    }

    @Override
    @NonNull
    public RemitChangeView rejectRemitChange(
            @NonNull UUID vendorId, @NonNull UUID changeId, @NonNull RemitRejectionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        SupplierVendorEntity vendor = loadVendor(vendorId);
        SupplierVendorRemitChangeEntity change = loadPendingChange(vendorId, changeId);
        change.setStatus(com.positivity.supplier.internal.enums.RemitChangeStatus.REJECTED);
        change.setDecidedBy(currentActor());
        change.setDecidedAt(Instant.now(clock));
        change.setDecisionNote(request.note());
        change = remitChangeRepository.saveAndFlush(change);
        log.info(
                "Remit-to change {} rejected for vendor {}: remit-to version stays {}",
                changeId,
                vendor.getVendorNumber(),
                vendor.getRemitToVersion());
        return toView(change);
    }

    // ── Facts replay (ADR-0044 §4) ──────────────────────────────────────────────────

    @Override
    @NonNull
    public VendorFactReplayResult replayFacts(@Nullable UUID afterVendorId, int limit) {
        int pageSize = Math.min(Math.max(limit, 1), MAX_REPLAY_LIMIT);
        Instant startedAt = Instant.now(clock);
        // One row beyond the page tells whether another page exists without a count query.
        PageRequest window = PageRequest.of(0, pageSize + 1);
        List<SupplierVendorEntity> found = afterVendorId == null
                ? vendorRepository.findAllByOrderByVendorIdAsc(window)
                : vendorRepository.findByVendorIdGreaterThanOrderByVendorIdAsc(afterVendorId, window);
        boolean complete = found.size() <= pageSize;
        List<SupplierVendorEntity> page = complete ? found : found.subList(0, pageSize);
        String actor = currentActor();
        for (SupplierVendorEntity vendor : page) {
            factPublisher.publish(vendor, startedAt, actor);
        }
        UUID next = complete || page.isEmpty() ? null : page.getLast().getVendorId();
        log.info(
                "Vendor fact replay after={} limit={} emitted={} complete={}",
                afterVendorId,
                pageSize,
                page.size(),
                complete);
        return new VendorFactReplayResult(page.size(), next, complete, startedAt);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────────

    /** The unique key on {@code (tenant_id, vendor_number)} in {@code V3__vendor_master.sql}. */
    static final String VENDOR_NUMBER_KEY = "supplier_vendor_number_key";

    /** Whether an integrity violation is the vendor-number key — and only that — being hit. */
    static boolean isVendorNumberTaken(@NonNull DataIntegrityViolationException failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation) {
                return VENDOR_NUMBER_KEY.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }

    private VendorView commitAndPublish(SupplierVendorEntity vendor) {
        SupplierVendorEntity saved = vendorRepository.saveAndFlush(vendor);
        factPublisher.publish(saved, Instant.now(clock), currentActor());
        return toView(saved);
    }

    @NonNull
    private SupplierVendorEntity loadVendor(@NonNull UUID vendorId) {
        Objects.requireNonNull(vendorId, "vendorId must not be null");
        return vendorRepository
                .findById(vendorId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.VENDOR_NOT_FOUND, VENDOR + vendorId + " does not exist"));
    }

    @NonNull
    private SupplierVendorRemitChangeEntity loadPendingChange(@NonNull UUID vendorId, @NonNull UUID changeId) {
        Objects.requireNonNull(changeId, "changeId must not be null");
        SupplierVendorRemitChangeEntity change = remitChangeRepository
                .findByChangeIdAndVendorId(changeId, vendorId)
                .orElseThrow(() -> new SupplierNotFoundException(
                        SupplierNotFoundException.VENDOR_REMIT_CHANGE_NOT_FOUND,
                        "Remit-to change " + changeId + " does not exist on vendor " + vendorId));
        if (change.getStatus() != com.positivity.supplier.internal.enums.RemitChangeStatus.PENDING) {
            throw new SupplierConflictException(
                    SupplierConflictException.VENDOR_REMIT_CHANGE_NOT_PENDING,
                    "Remit-to change " + changeId + " was already " + change.getStatus() + ".");
        }
        return change;
    }

    /** The principal name, the form pos-accounting records for approvers (S24 compares them). */
    @NonNull
    private static String currentActor() {
        return SecurityContextHelper.getCurrentUsernameOrDefault("system");
    }

    private static SupplierConflictException numberTaken(String vendorNumber) {
        return new SupplierConflictException(
                SupplierConflictException.VENDOR_NUMBER_TAKEN,
                "Vendor number " + vendorNumber + " is already used by another vendor.");
    }

    private static SupplierConflictException changePending(SupplierVendorEntity vendor) {
        return new SupplierConflictException(
                SupplierConflictException.VENDOR_REMIT_CHANGE_PENDING,
                VENDOR + vendor.getVendorNumber()
                        + " already has a remit-to change waiting for approval; decide that one first.");
    }

    private static SupplierValidationException invalid(String message) {
        return new SupplierValidationException(SupplierValidationException.VALIDATION_ERROR, message);
    }

    /**
     * Replaces the stored registrations when the request changes them (ADR-0072 Decision 4, IC-004). A supplied number
     * is always a change, even under an unchanged id, scheme and region: it was re-sealed and its {@code last4}
     * recomputed, and the masked fact is published. Otherwise the decision compares registration ids and their
     * stored attributes (scheme, region) only, never ciphertext or {@code last4}. In place: Hibernate tracks the
     * managed list instance.
     */
    private static void replaceTaxRegistrations(
            SupplierVendorEntity vendor, List<VendorTaxRegistration> registrations, boolean valueSupplied) {
        if (valueSupplied || !sameIdsAndAttributes(vendor.getTaxRegistrations(), registrations)) {
            vendor.setTaxRegistrations(new ArrayList<>(registrations));
        }
    }

    private static boolean sameIdsAndAttributes(List<VendorTaxRegistration> stored, List<VendorTaxRegistration> next) {
        if (stored.size() != next.size()) {
            return false;
        }
        for (int i = 0; i < stored.size(); i++) {
            VendorTaxRegistration before = stored.get(i);
            VendorTaxRegistration after = next.get(i);
            if (!before.registrationId().equals(after.registrationId())
                    || !before.scheme().equals(after.scheme())
                    || !Objects.equals(before.region(), after.region())) {
                return false;
            }
        }
        return true;
    }

    /**
     * The stored registrations a create or update produces (Security ruling on #2617, ruling 4):
     *
     * <ul>
     *   <li>{@code registrationId} without a number keeps the stored element as it is; its scheme and region
     *       must equal the stored ones, because a changed scheme or region on an unseen number would relabel it;
     *   <li>{@code registrationId} with a number re-seals that registration under the same id;
     *   <li>no {@code registrationId} is a new registration under a new UUIDv7 and needs its number;
     *   <li>an id this vendor does not hold, or one sent twice, is refused; a stored id left out is removed.
     * </ul>
     *
     * <p>Every entry that carries a number has its (already upper-cased) scheme and region checked against
     * {@link VendorTaxRegistrationShapes} (ADR-0072 Decision 2).
     *
     * <p>Every refusal is a 400 {@code VALIDATION_ERROR} naming {@code taxRegistrations[i].<field>} and never
     * carries the number, the scheme or the region.
     */
    private List<VendorTaxRegistration> resolveTaxRegistrations(
            UUID tenantId, UUID vendorId, List<VendorTaxRegistration> stored, List<TaxRegistrationDto> requested) {
        Map<UUID, VendorTaxRegistration> storedById = new LinkedHashMap<>();
        for (VendorTaxRegistration registration : stored) {
            storedById.put(registration.registrationId(), registration);
        }
        Set<UUID> seen = new HashSet<>();
        List<VendorTaxRegistration> resolved = new ArrayList<>();
        for (int i = 0; i < requested.size(); i++) {
            TaxRegistrationDto entry = requested.get(i);
            String field = "taxRegistrations[" + i + "].";
            if (entry.number() != null) {
                requireShapes(entry, field);
            }
            UUID registrationId = entry.registrationId();
            if (registrationId == null) {
                if (entry.number() == null) {
                    throw fieldInvalid(field + "number", "a new registration needs its number");
                }
                resolved.add(seal(tenantId, vendorId, UUIDv7Generator.generate(), entry));
                continue;
            }
            VendorTaxRegistration existing = storedById.get(registrationId);
            if (existing == null) {
                throw fieldInvalid(field + "registrationId", "is not a registration of this vendor");
            }
            if (!seen.add(registrationId)) {
                throw fieldInvalid(field + "registrationId", "is sent more than once");
            }
            if (entry.number() == null) {
                if (!existing.scheme().equals(entry.scheme()) || !Objects.equals(existing.region(), entry.region())) {
                    throw fieldInvalid(field + "number", "re-enter the number to change its scheme or region");
                }
                resolved.add(existing);
            } else {
                resolved.add(seal(tenantId, vendorId, registrationId, entry));
            }
        }
        return resolved;
    }

    /** The value is never echoed: the message names the rule only. */
    private static void requireShapes(TaxRegistrationDto entry, String field) {
        if (!VendorTaxRegistrationShapes.SCHEME.matcher(entry.scheme()).matches()) {
            throw fieldInvalid(
                    field + "scheme",
                    "must be letters, spaces, _, / or -, start with a letter, at most 16 characters, and no digit");
        }
        if (entry.region() != null
                && !VendorTaxRegistrationShapes.REGION.matcher(entry.region()).matches()) {
            throw fieldInvalid(field + "region", "must be two letters, optionally - and one to three letters");
        }
    }

    private VendorTaxRegistration seal(UUID tenantId, UUID vendorId, UUID registrationId, TaxRegistrationDto entry) {
        String number = Objects.requireNonNull(entry.number(), "number");
        return new VendorTaxRegistration(
                registrationId,
                entry.scheme(),
                entry.region(),
                VendorTaxRegistration.last4Of(number),
                taxIdCipher.seal(tenantId, vendorId, registrationId, number));
    }

    private static SupplierValidationException fieldInvalid(String field, String message) {
        return new SupplierValidationException(
                SupplierValidationException.VALIDATION_ERROR,
                "Tax registration refused: " + field + " " + message,
                List.of(new ApiError.FieldError(field, message)));
    }

    private static VendorRemitTo toEntity(RemitToDto remitTo) {
        return new VendorRemitTo(
                remitTo.payeeName(),
                remitTo.addressLine1(),
                remitTo.addressLine2(),
                remitTo.city(),
                remitTo.region(),
                remitTo.postalCode(),
                remitTo.countryCode(),
                remitTo.remittanceEmail());
    }

    private static @Nullable RemitToDto toDto(@Nullable VendorRemitTo remitTo) {
        if (remitTo == null) {
            return null;
        }
        return new RemitToDto(
                remitTo.payeeName(),
                remitTo.addressLine1(),
                remitTo.addressLine2(),
                remitTo.city(),
                remitTo.region(),
                remitTo.postalCode(),
                remitTo.countryCode(),
                remitTo.remittanceEmail());
    }

    static VendorView toView(SupplierVendorEntity vendor) {
        return new VendorView(
                vendor.getVendorId(),
                vendor.getVendorNumber(),
                vendor.getLegalName(),
                vendor.getDisplayName(),
                vendor.getTaxRegistrations().stream()
                        .map(registration -> new TaxRegistrationView(
                                registration.registrationId(),
                                registration.scheme(),
                                registration.region(),
                                registration.last4()))
                        .toList(),
                toDto(vendor.getRemitTo()),
                vendor.getRemitToVersion(),
                vendor.getRemitToChangedAt(),
                vendor.getRemitToRequestedBy(),
                vendor.getRemitToApprovedBy(),
                vendor.getDefaultPaymentTerms(),
                vendor.getDefaultCurrency(),
                VendorStatus.valueOf(vendor.getStatus().name()),
                vendor.getStatusChangedAt(),
                vendor.getStatusReason(),
                vendor.getCreatedAt(),
                vendor.getCreatedBy(),
                vendor.getUpdatedAt(),
                vendor.getUpdatedBy(),
                vendor.getVersion() == null ? 0L : vendor.getVersion());
    }

    static RemitChangeView toView(SupplierVendorRemitChangeEntity change) {
        RemitToDto proposed = Objects.requireNonNull(toDto(change.getProposedRemitTo()), "proposed remit-to");
        return new RemitChangeView(
                change.getChangeId(),
                change.getVendorId(),
                proposed,
                change.getReason(),
                RemitChangeStatus.valueOf(change.getStatus().name()),
                change.getFromVersion(),
                change.getToVersion(),
                change.getRequestedBy(),
                change.getRequestedAt(),
                change.getDecidedBy(),
                change.getDecidedAt(),
                change.getDecisionNote());
    }
}
