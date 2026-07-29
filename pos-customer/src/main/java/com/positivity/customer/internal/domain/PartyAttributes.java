package com.positivity.customer.internal.domain;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A single party's values for every attribute in the segment catalog (Story #1137).
 *
 * <p>Built once per candidate by batch queries, then evaluated against the predicate in
 * memory. Materializing the snapshot up front is what keeps resolution to a fixed handful of
 * queries instead of one per party per attribute.
 *
 * @param vehicleMakes distinct makes across the party's active vehicles — a predicate on
 *     {@code vehicle.make} matches if <em>any</em> vehicle matches, which is what a marketer
 *     targeting "customers with a Ford" means
 * @param monthsSinceLastService whole months since the most recent completed service, or null
 *     when the party has no service history (FI-3, #1133)
 * @param hasServiceHistory whether the party has any completed-service history
 * @param daysSinceDeclinedService whole days since the most recent declined recommendation, or
 *     null when the party has never declined one (FI-3, #1133)
 * @param addressCountry ISO 3166-1 alpha-2 country code of the party's structured postal
 *     address, or null when none is on file (FI-4, #1135) — individuals read the
 *     ext_people_contact_person replica, commercial parties ext_organization_postal_address
 * @param addressRegion free-text administrative area (state/province/prefecture) of the
 *     structured address, or null
 * @param addressCity city/locality of the structured address, or null
 * @param addressPostalCode postal code of the structured address, or null — also null for
 *     countries that do not use one, so prefer country/region predicates for broad targeting
 */
public record PartyAttributes(
        UUID partyId,
        String partyType,
        @Nullable String accountTier,
        @Nullable String accountStatus,
        boolean hasParentParty,
        Map<String, String> externalIdentifiers,
        Set<UUID> tagIds,
        @Nullable Boolean taxExempt,
        @Nullable Boolean creditHold,
        @Nullable String paymentTerms,
        String marketingEmailConsent,
        String marketingSmsConsent,
        Set<String> vehicleMakes,
        Set<String> vehicleModels,
        Set<Integer> vehicleYears,
        boolean hasActiveVehicle,
        long vehicleCount,
        @Nullable String displayLabel,
        @Nullable Integer monthsSinceLastService,
        boolean hasServiceHistory,
        @Nullable Integer daysSinceDeclinedService,
        @Nullable String addressCountry,
        @Nullable String addressRegion,
        @Nullable String addressCity,
        @Nullable String addressPostalCode) {}
