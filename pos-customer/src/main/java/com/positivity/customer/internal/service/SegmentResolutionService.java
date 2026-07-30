package com.positivity.customer.internal.service;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.domain.SegmentPredicateEvaluator;
import com.positivity.customer.internal.entity.CommercialParty;
import com.positivity.customer.internal.entity.CommunicationPreference;
import com.positivity.customer.internal.entity.ExtOrganizationPostalAddress;
import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.entity.ExtVehicle;
import com.positivity.customer.internal.entity.PartyTagAssignment;
import com.positivity.customer.internal.entity.PersonParty;
import com.positivity.customer.internal.entity.Segment;
import com.positivity.customer.internal.enums.AudienceType;
import com.positivity.customer.internal.enums.MarketingConsent;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns a segment definition into the concrete set of parties it matches (Story #1137).
 *
 * <p>Resolution loads the audience's candidate parties and their attribute snapshots in a
 * fixed number of batch queries, then evaluates the predicate in memory. This is deliberately
 * not a translated-to-SQL query builder: the predicate tree is authored data, and keeping it
 * away from generated SQL is what makes an arbitrary stored predicate safe to run. The cost is
 * linear in the candidate pool, which is bounded by {@link #MAX_CANDIDATES}.
 *
 * <p>Ordering is by party id so repeated resolutions of an unchanged segment return the same
 * list — a campaign that pages through an audience must not see rows shift underneath it.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SegmentResolutionService {

    /**
     * Ceiling on the candidate pool scanned in one resolution. Beyond this the result is
     * truncated and flagged rather than silently partial, so a marketer never believes an
     * audience is smaller than it is.
     */
    public static final int MAX_CANDIDATES = 50_000;

    private final CommercialPartyRepository commercialPartyRepository;
    private final PersonPartyRepository personPartyRepository;
    private final CommunicationPreferenceRepository preferenceRepository;
    private final ExtVehicleRepository extVehicleRepository;
    private final PartyTagAssignmentRepository tagAssignmentRepository;
    private final SegmentMemberRepository segmentMemberRepository;
    private final ServiceHistoryRepository serviceHistoryRepository;
    private final FollowUpTaskRepository followUpTaskRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtOrganizationPostalAddressRepository extOrganizationPostalAddressRepository;
    private final Clock clock;

    /** Matched party ids plus whether the candidate scan hit the ceiling. */
    public record Resolution(List<UUID> partyIds, boolean truncated) {}

    @Transactional(readOnly = true)
    public @NonNull Resolution resolve(@NonNull Segment segment, @NonNull Optional<SegmentPredicate> predicate) {
        return segment.getType() == SegmentType.STATIC
                ? resolveStatic(segment)
                : resolveDynamic(
                        segment,
                        predicate.orElseThrow(() -> new IllegalStateException(
                                "Dynamic segment " + segment.getSegmentId() + " has no predicate")));
    }

    /**
     * Static members are still filtered by audience type. A party can be reclassified or
     * deleted after it was pinned to the list, and sending to a party that no longer fits the
     * audience produces a message written for the wrong kind of customer.
     */
    private Resolution resolveStatic(Segment segment) {
        List<UUID> members = segmentMemberRepository.findPartyIdsBySegmentId(segment.getSegmentId());
        Set<UUID> valid = audienceMemberIds(segment.getAudienceType(), members);
        List<UUID> ordered =
                members.stream().filter(valid::contains).distinct().sorted().toList();
        if (ordered.size() < members.size()) {
            log.info(
                    "Segment {} dropped {} static member(s) that no longer match audience {}",
                    segment.getSegmentId(),
                    members.size() - ordered.size(),
                    segment.getAudienceType());
        }
        return new Resolution(ordered, false);
    }

    private Resolution resolveDynamic(Segment segment, SegmentPredicate predicate) {
        List<PartyAttributes> candidates = loadCandidates(segment.getAudienceType());
        boolean truncated = candidates.size() >= MAX_CANDIDATES;
        List<UUID> matched = candidates.stream()
                .filter(party -> SegmentPredicateEvaluator.matches(predicate, party))
                .map(PartyAttributes::partyId)
                .sorted()
                .toList();
        if (truncated) {
            log.warn(
                    "Segment {} resolution scanned the {}-party ceiling; result is truncated",
                    segment.getSegmentId(),
                    MAX_CANDIDATES);
        }
        return new Resolution(matched, truncated);
    }

    /** Attribute snapshots for every candidate in the audience, built in a fixed query count. */
    @Transactional(readOnly = true)
    public @NonNull List<PartyAttributes> loadCandidates(@NonNull AudienceType audienceType) {
        return audienceType == AudienceType.COMMERCIAL ? loadCommercialCandidates() : loadIndividualCandidates();
    }

    /** Snapshots for a known party set — used by audience preview and by static-member checks. */
    @Transactional(readOnly = true)
    public @NonNull List<PartyAttributes> loadAttributes(
            @NonNull AudienceType audienceType, @NonNull List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return List.of();
        }
        Set<UUID> wanted = new HashSet<>(partyIds);
        return loadCandidates(audienceType).stream()
                .filter(party -> wanted.contains(party.partyId()))
                .toList();
    }

    private List<PartyAttributes> loadCommercialCandidates() {
        List<CommercialParty> accounts = commercialPartyRepository.findAll().stream()
                .limit(MAX_CANDIDATES)
                .toList();
        List<UUID> ids = accounts.stream().map(CommercialParty::getPartyId).toList();
        Map<UUID, CommunicationPreference> preferences = preferencesByParty(ids);
        Map<UUID, Set<UUID>> tags = tagsByParty(ids);
        Map<UUID, List<ExtVehicle>> vehicles = vehiclesByAccount(ids);
        Map<UUID, Instant> lastService = lastServiceByParty(ids);
        Map<UUID, Instant> lastDeclined = lastDeclinedByParty(ids);
        // FI-4 (#1135): organization addresses replicate from pos-people-contact keyed by the
        // commercial party id this module minted.
        Map<UUID, ExtOrganizationPostalAddress> addresses = orgAddressesByParty(ids);

        return accounts.stream()
                .map(account -> {
                    List<ExtVehicle> owned = vehicles.getOrDefault(account.getPartyId(), List.of());
                    CommunicationPreference preference = preferences.get(account.getPartyId());
                    ExtOrganizationPostalAddress address = addresses.get(account.getPartyId());
                    return new PartyAttributes(
                            account.getPartyId(),
                            account.getPartyType() != null
                                    ? account.getPartyType().name()
                                    : "COMMERCIAL",
                            account.getTier() != null ? account.getTier().name() : null,
                            account.getStatus() != null ? account.getStatus().name() : null,
                            account.getParentParty() != null,
                            account.getExternalIdentifiers() != null
                                    ? Map.copyOf(account.getExternalIdentifiers())
                                    : Map.of(),
                            tags.getOrDefault(account.getPartyId(), Set.of()),
                            account.getBillingRules() != null
                                    ? account.getBillingRules().getTaxExempt()
                                    : null,
                            account.getBillingRules() != null
                                    ? account.getBillingRules().getCreditHold()
                                    : null,
                            account.getBillingRules() != null
                                    ? account.getBillingRules().getPaymentTerms()
                                    : null,
                            consent(preference, true),
                            consent(preference, false),
                            distinct(owned, ExtVehicle::getMake),
                            distinct(owned, ExtVehicle::getModel),
                            owned.stream()
                                    .map(ExtVehicle::getYear)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(Collectors.toSet()),
                            owned.stream().anyMatch(ExtVehicle::isActive),
                            owned.size(),
                            account.getDisplayName() != null ? account.getDisplayName() : account.getLegalName(),
                            monthsSince(lastService.get(account.getPartyId())),
                            lastService.containsKey(account.getPartyId()),
                            daysSince(lastDeclined.get(account.getPartyId())),
                            address != null ? address.getCountryCode() : null,
                            address != null ? address.getRegion() : null,
                            address != null ? address.getCity() : null,
                            address != null ? address.getPostalCode() : null);
                })
                .toList();
    }

    private List<PartyAttributes> loadIndividualCandidates() {
        List<PersonParty> people =
                personPartyRepository.findAll().stream().limit(MAX_CANDIDATES).toList();
        List<UUID> ids = people.stream().map(PersonParty::getPartyId).toList();
        Map<UUID, CommunicationPreference> preferences = preferencesByParty(ids);
        Map<UUID, Set<UUID>> tags = tagsByParty(ids);
        Map<UUID, List<ExtVehicle>> vehicles = vehiclesByAccount(ids);
        Map<UUID, Instant> lastService = lastServiceByParty(ids);
        Map<UUID, Instant> lastDeclined = lastDeclinedByParty(ids);
        // FI-4 (#1135): individual addresses live on the person identity replica, keyed by the
        // people-contact person id, not the local party id.
        Map<UUID, ExtPersonReplica> personReplicas = personReplicasByPersonId(people);

        return people.stream()
                .map(person -> {
                    List<ExtVehicle> owned = vehicles.getOrDefault(person.getPartyId(), List.of());
                    CommunicationPreference preference = preferences.get(person.getPartyId());
                    ExtPersonReplica replica = personReplicas.get(person.getPersonId());
                    return new PartyAttributes(
                            person.getPartyId(),
                            "PERSON",
                            person.getTier() != null ? person.getTier().name() : null,
                            person.getStatus() != null ? person.getStatus().name() : null,
                            false,
                            Map.of(),
                            tags.getOrDefault(person.getPartyId(), Set.of()),
                            null,
                            null,
                            null,
                            consent(preference, true),
                            consent(preference, false),
                            distinct(owned, ExtVehicle::getMake),
                            distinct(owned, ExtVehicle::getModel),
                            owned.stream()
                                    .map(ExtVehicle::getYear)
                                    .filter(java.util.Objects::nonNull)
                                    .collect(Collectors.toSet()),
                            owned.stream().anyMatch(ExtVehicle::isActive),
                            owned.size(),
                            // Person names live in pos-people-contact (ADR-0015); the customer
                            // number is the only local label, and it is already non-identifying.
                            person.getCustomerNumber(),
                            monthsSince(lastService.get(person.getPartyId())),
                            lastService.containsKey(person.getPartyId()),
                            daysSince(lastDeclined.get(person.getPartyId())),
                            replica != null ? replica.getAddressCountryCode() : null,
                            replica != null ? replica.getAddressRegion() : null,
                            replica != null ? replica.getAddressCity() : null,
                            replica != null ? replica.getAddressPostalCode() : null);
                })
                .toList();
    }

    private Set<UUID> audienceMemberIds(AudienceType audienceType, List<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return Set.of();
        }
        return audienceType == AudienceType.COMMERCIAL
                ? commercialPartyRepository.findAllById(candidateIds).stream()
                        .map(CommercialParty::getPartyId)
                        .collect(Collectors.toSet())
                : personPartyRepository.findAllById(candidateIds).stream()
                        .map(PersonParty::getPartyId)
                        .collect(Collectors.toSet());
    }

    private Map<UUID, CommunicationPreference> preferencesByParty(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, CommunicationPreference> byParty = new HashMap<>();
        preferenceRepository.findByPartyIdIn(partyIds).forEach(pref -> byParty.put(pref.getPartyId(), pref));
        return byParty;
    }

    private Map<UUID, Set<UUID>> tagsByParty(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> byParty = new HashMap<>();
        for (PartyTagAssignment assignment : tagAssignmentRepository.findByPartyIdIn(partyIds)) {
            byParty.computeIfAbsent(assignment.getPartyId(), key -> new HashSet<>())
                    .add(assignment.getTagId());
        }
        return byParty;
    }

    private Map<UUID, ExtOrganizationPostalAddress> orgAddressesByParty(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ExtOrganizationPostalAddress> byParty = new HashMap<>();
        extOrganizationPostalAddressRepository
                .findAllById(partyIds)
                .forEach(address -> byParty.put(address.getOrganizationId(), address));
        return byParty;
    }

    private Map<UUID, ExtPersonReplica> personReplicasByPersonId(List<PersonParty> people) {
        List<UUID> personIds = people.stream()
                .map(PersonParty::getPersonId)
                .filter(java.util.Objects::nonNull)
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ExtPersonReplica> byPersonId = new HashMap<>();
        extPersonReplicaRepository
                .findAllById(personIds)
                .forEach(replica -> byPersonId.put(replica.getPersonId(), replica));
        return byPersonId;
    }

    private Map<UUID, List<ExtVehicle>> vehiclesByAccount(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        return extVehicleRepository.findByAccountIdIn(partyIds).stream()
                .collect(Collectors.groupingBy(ExtVehicle::getAccountId));
    }

    private Map<UUID, Instant> lastServiceByParty(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> byParty = new HashMap<>();
        for (var row : serviceHistoryRepository.findLastServiceByParty(partyIds)) {
            if (row.getLastCompletedAt() != null) {
                byParty.put(row.getPartyId(), row.getLastCompletedAt());
            }
        }
        return byParty;
    }

    private Map<UUID, Instant> lastDeclinedByParty(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Instant> byParty = new HashMap<>();
        for (var row : followUpTaskRepository.findLastDeclinedByParty(partyIds)) {
            if (row.getLastDeclinedAt() != null) {
                byParty.put(row.getPartyId(), row.getLastDeclinedAt());
            }
        }
        return byParty;
    }

    /** Whole months elapsed since {@code since} (UTC calendar), or null when absent; floored at 0. */
    private @Nullable Integer monthsSince(@Nullable Instant since) {
        if (since == null) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(LocalDate.ofInstant(since, ZoneOffset.UTC), LocalDate.now(clock));
        return (int) Math.max(0, months);
    }

    /** Whole days elapsed since {@code since} (UTC calendar), or null when absent; floored at 0. */
    private @Nullable Integer daysSince(@Nullable Instant since) {
        if (since == null) {
            return null;
        }
        long days = ChronoUnit.DAYS.between(LocalDate.ofInstant(since, ZoneOffset.UTC), LocalDate.now(clock));
        return (int) Math.max(0, days);
    }

    private static String consent(CommunicationPreference preference, boolean email) {
        if (preference == null) {
            return MarketingConsent.UNSET.name();
        }
        MarketingConsent value = email ? preference.getMarketingEmailConsent() : preference.getMarketingSmsConsent();
        return (value != null ? value : MarketingConsent.UNSET).name();
    }

    private static Set<String> distinct(
            List<ExtVehicle> vehicles, java.util.function.Function<ExtVehicle, String> accessor) {
        return vehicles.stream()
                .map(accessor)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
