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
 * Resolves UUID-backed references to the human-readable identities accounting responses show in
 * their place (issues #1778, #1779).
 *
 * <p>Every source is data this module already holds — its own records, or a replica fed by the
 * owner's domain events — so resolution crosses no domain wall and issues no synchronous call to
 * another service (ADR-0044). What accounting cannot name stays unnamed: a missing source yields
 * {@link ResolvedDisplayReference#EMPTY}, never the UUID rendered as text. Callers keep the raw
 * identifier alongside the display values for commands, links and audit.
 *
 * <p>Resolution is batched per type: one {@code IN} query per reference type per response, so a
 * page of rows costs the same number of queries as a single row.
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
     * Batch-resolve display values for one reference type.
     *
     * @param type reference type to resolve
     * @param ids  identifiers to resolve; nulls and duplicates are ignored
     * @return display values by identifier — identifiers with nothing to show are absent from the
     *         map, so callers should read it with {@code getOrDefault(id, EMPTY)}
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
            case LOCATION -> resolveLocations(distinct);
            // ADR-0023 retired multi-tenancy: there is no organization directory to name an
            // organizationId from, so the type is recognized by the contract and always resolves
            // to nothing. Recognizing it costs one branch and means a future directory is a
            // resolver change, not a wire-contract change.
            case ORGANIZATION -> Map.of();
        };
    }

    /**
     * Locations are keyed in {@code accounting_location_profile} by the accounting location
     * <em>code</em> ({@code LOC-107}), which is the value the journal-entry-line {@code locationId}
     * dimension carries. A payload holding a raw location UUID therefore resolves only where a
     * profile happens to be coded with that same string; otherwise it stays unnamed, which is the
     * honest answer rather than echoing the UUID.
     */
    private Map<UUID, ResolvedDisplayReference> resolveLocations(Set<UUID> ids) {
        Map<String, UUID> byCode = new HashMap<>();
        for (UUID id : ids) {
            byCode.put(id.toString(), id);
        }
        Map<UUID, ResolvedDisplayReference> resolved = new HashMap<>();
        for (LocationProfile profile : locationProfileRepository.findByLocationCodeIn(byCode.keySet())) {
            UUID id = byCode.get(profile.getLocationCode());
            if (id == null) {
                continue;
            }
            ResolvedDisplayReference display =
                    normalize(new ResolvedDisplayReference(profile.getLocationLabel(), profile.getLocationCode()));
            if (!display.isEmpty()) {
                resolved.put(id, display);
            }
        }
        return resolved;
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
