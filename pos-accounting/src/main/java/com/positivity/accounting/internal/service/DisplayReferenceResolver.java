package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ResolvedDisplayReference;
import com.positivity.accounting.internal.entity.ExtCustomerParty;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.LocationProfile;
import com.positivity.accounting.internal.entity.Vendor;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.DisplayReferenceType;
import com.positivity.accounting.internal.repository.ExtCustomerPartyRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.LocationProfileRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.repository.VendorRepository;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Resolves references to the human-readable identities accounting responses show in their place
 * (issues #1778, #1779, #1797).
 *
 * <p>Every source is data this module already holds — its own records, or a replica fed by the
 * owner's domain events — so resolution crosses no domain wall and issues no synchronous call to
 * another service (ADR-0044). What accounting cannot name stays unnamed: a missing source yields
 * {@link ResolvedDisplayReference#EMPTY}, never the identifier rendered as text. Callers keep the
 * raw identifier alongside the display values for commands, links and audit.
 *
 * <p>Resolution is batched per type: one {@code IN} query per reference type per response, so a
 * page of rows costs the same number of queries as a single row.
 *
 * <p>Types are keyed one of two ways, and each has its own entry point so a caller cannot look a
 * value up by the wrong shape: UUID-keyed types go through {@link #resolve}; code-keyed types
 * ({@link DisplayReferenceType#isCodeKeyed()}) go through {@link #resolveCodes}.
 */
@Component
@RequiredArgsConstructor
public class DisplayReferenceResolver {

    private final ExtInvoiceRepository extInvoiceRepository;
    private final ExtCustomerPartyRepository extCustomerPartyRepository;
    private final JournalEntryRepository journalEntryRepository;
    private final LocationProfileRepository locationProfileRepository;
    private final VendorRepository vendorRepository;
    private final VendorBillRepository vendorBillRepository;

    /**
     * Batch-resolve display values for one UUID-keyed reference type.
     *
     * @param type reference type to resolve; must not be code-keyed
     * @param ids  identifiers to resolve; nulls and duplicates are ignored
     * @return display values by identifier — identifiers with nothing to show are absent from the
     *         map, so callers should read it with {@code getOrDefault(id, EMPTY)}
     * @throws IllegalArgumentException if {@code type} is code-keyed; use {@link #resolveCodes}
     */
    @NonNull
    @Transactional(readOnly = true)
    public Map<UUID, ResolvedDisplayReference> resolve(
            @NonNull DisplayReferenceType type, @NonNull Collection<UUID> ids) {

        Set<UUID> distinct = new LinkedHashSet<>();
        for (UUID id : ids) {
            if (id != null) {
                distinct.add(id);
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }

        return switch (type) {
            case INVOICE ->
                index(
                        extInvoiceRepository.findAllById(distinct),
                        ExtInvoice::getInvoiceId,
                        invoice -> ResolvedDisplayReference.ofReference(invoice.getInvoiceNumber()));
            case CUSTOMER ->
                index(
                        extCustomerPartyRepository.findAllById(distinct),
                        ExtCustomerParty::getPartyId,
                        party -> new ResolvedDisplayReference(party.getDisplayName(), party.getCustomerNumber()));
            case JOURNAL_ENTRY ->
                index(
                        journalEntryRepository.findAllById(distinct),
                        JournalEntry::getJournalEntryId,
                        entry -> ResolvedDisplayReference.ofReference(entry.getEntryNumber()));
            case VENDOR ->
                index(
                        vendorRepository.findAllById(distinct),
                        Vendor::getVendorId,
                        vendor -> new ResolvedDisplayReference(vendor.getName(), vendor.getVendorNumber()));
            case VENDOR_BILL ->
                index(
                        vendorBillRepository.findAllById(distinct),
                        VendorBill::getVendorBillId,
                        bill -> ResolvedDisplayReference.ofReference(bill.getBillNumber()));
            // ADR-0023 retired multi-tenancy: there is no organization directory to name an
            // organizationId from, so the type is recognized by the contract and always resolves
            // to nothing. Recognizing it costs one branch and means a future directory is a
            // resolver change, not a wire-contract change.
            case ORGANIZATION -> Map.of();
            case LOCATION -> throw codeKeyedMisuse(type);
        };
    }

    /**
     * Batch-resolve display values for one code-keyed reference type.
     *
     * <p>Codes are matched case-insensitively, since event producers do not all spell a code the
     * way accounting's master data stores it. The returned map is keyed by the codes exactly as
     * given, so a caller correlates results without re-normalizing; the resolved
     * {@code displayReference} is the canonical stored code.
     *
     * @param type  reference type to resolve; must be code-keyed
     * @param codes business codes to resolve; nulls, blanks and duplicates are ignored
     * @return display values by the given code — codes with nothing to show are absent from the
     *         map, so callers should read it with {@code getOrDefault(code, EMPTY)}
     * @throws IllegalArgumentException if {@code type} is UUID-keyed; use {@link #resolve}
     */
    @NonNull
    @Transactional(readOnly = true)
    public Map<String, ResolvedDisplayReference> resolveCodes(
            @NonNull DisplayReferenceType type, @NonNull Collection<String> codes) {

        Set<String> distinct = new LinkedHashSet<>();
        for (String code : codes) {
            if (code != null && !code.isBlank()) {
                distinct.add(code);
            }
        }
        if (distinct.isEmpty()) {
            return Map.of();
        }

        return switch (type) {
            case LOCATION -> resolveLocations(distinct);
            case INVOICE, CUSTOMER, ORGANIZATION, JOURNAL_ENTRY, VENDOR, VENDOR_BILL ->
                throw new IllegalArgumentException(
                        type + " is UUID-keyed; resolve it through resolve(type, ids) rather than by code");
        };
    }

    /**
     * Locations are keyed in {@code accounting_location_profile} by the accounting location
     * <em>code</em> ({@code LOC-107}), which is the value the journal-entry-line and event
     * {@code locationId} dimension carries. One upper-cased {@code IN} query finds every profile;
     * each given code is then matched back to its profile by the same normalization, so
     * {@code loc-107} in a payload names the profile stored as {@code LOC-107}.
     */
    private Map<String, ResolvedDisplayReference> resolveLocations(Set<String> codes) {
        Map<String, String> normalizedByCode = new HashMap<>();
        for (String code : codes) {
            normalizedByCode.put(code, normalizeCode(code));
        }

        Map<String, LocationProfile> profilesByNormalizedCode = new HashMap<>();
        for (LocationProfile profile : locationProfileRepository.findByLocationCodeInIgnoreCase(
                new LinkedHashSet<>(normalizedByCode.values()))) {
            profilesByNormalizedCode.put(normalizeCode(profile.getLocationCode()), profile);
        }

        Map<String, ResolvedDisplayReference> resolved = new HashMap<>();
        normalizedByCode.forEach((code, normalized) -> {
            LocationProfile profile = profilesByNormalizedCode.get(normalized);
            if (profile == null) {
                return;
            }
            ResolvedDisplayReference display =
                    normalize(new ResolvedDisplayReference(profile.getLocationLabel(), profile.getLocationCode()));
            if (!display.isEmpty()) {
                resolved.put(code, display);
            }
        });
        return resolved;
    }

    /** The comparison key for a location code: trimmed and upper-cased, matching the JPQL. */
    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static IllegalArgumentException codeKeyedMisuse(DisplayReferenceType type) {
        return new IllegalArgumentException(
                type + " is code-keyed; resolve it through resolveCodes(type, codes) rather than by UUID");
    }

    /**
     * Index resolved rows by identifier, dropping anything that carries no display value at all —
     * an entry that would render as nothing is indistinguishable from an unresolved one, and
     * leaving it out keeps {@code getOrDefault(id, EMPTY)} correct for both.
     */
    private static <T> Map<UUID, ResolvedDisplayReference> index(
            List<T> rows, Function<T, UUID> idOf, Function<T, ResolvedDisplayReference> displayOf) {

        Map<UUID, ResolvedDisplayReference> resolved = new HashMap<>();
        for (T row : rows) {
            UUID id = idOf.apply(row);
            if (id == null) {
                continue;
            }
            ResolvedDisplayReference display = normalize(displayOf.apply(row));
            if (!display.isEmpty()) {
                resolved.put(id, display);
            }
        }
        return resolved;
    }

    /** Blank source values are absent values: normalize them so callers only ever see null. */
    private static ResolvedDisplayReference normalize(ResolvedDisplayReference display) {
        return new ResolvedDisplayReference(
                blankToNull(display.displayName()), blankToNull(display.displayReference()));
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
