package com.positivity.customer.internal.service;

import com.positivity.customer.internal.domain.PartyAttributes;
import com.positivity.customer.internal.domain.SegmentPredicate;
import com.positivity.customer.internal.domain.SegmentPredicateEvaluator;
import com.positivity.customer.internal.entity.AbstractParty;
import com.positivity.customer.internal.entity.BillingRulesEmbeddable;
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
import com.positivity.customer.internal.enums.PartyType;
import com.positivity.customer.internal.enums.SegmentType;
import com.positivity.customer.internal.repository.CommercialPartyRepository;
import com.positivity.customer.internal.repository.CommunicationPreferenceRepository;
import com.positivity.customer.internal.repository.ExtOrganizationPostalAddressRepository;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ExtVehicleCarePreferenceRepository;
import com.positivity.customer.internal.repository.ExtVehicleRepository;
import com.positivity.customer.internal.repository.FollowUpTaskRepository;
import com.positivity.customer.internal.repository.PartyTagAssignmentRepository;
import com.positivity.customer.internal.repository.PersonPartyRepository;
import com.positivity.customer.internal.repository.SegmentMemberRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository;
import com.positivity.customer.internal.repository.ServiceHistoryRepository.PartyVehicleLastServiceView;
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
import org.springframework.beans.factory.annotation.Value;
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

    /** Projected party type when a commercial account's own column is unset. */
    private static final String DEFAULT_COMMERCIAL_TYPE = PartyType.COMMERCIAL.name();

    /** Individuals have no stored party type; the audience they came from is the type. */
    private static final String INDIVIDUAL_TYPE = PartyType.PERSON.name();

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
    private final ExtVehicleCarePreferenceRepository extVehicleCarePreferenceRepository;
    private final Clock clock;

    /**
     * Fallback service-due interval in whole months (#1144). Per-vehicle care-preference
     * intervals replicate from pos-vehicle-inventory into {@code ext_vehicle_care_preference}
     * (#1175) and take precedence where vehicle-scoped history exists; this module-wide interval
     * covers vehicles without an override and vehicle-less service history.
     */
    @Value("${pos.customer.crm.service-due-months:6}")
    private int serviceDueMonths = 6;

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
        SharedFacts facts = loadSharedFacts(ids);
        // FI-4 (#1135): organization addresses replicate from pos-people-contact keyed by the
        // commercial party id this module minted.
        Map<UUID, ExtOrganizationPostalAddress> addresses = orgAddressesByParty(ids);

        return accounts.stream()
                .map(account -> toAttributes(account, facts, AddressSnapshot.of(addresses.get(account.getPartyId()))))
                .toList();
    }

    private List<PartyAttributes> loadIndividualCandidates() {
        List<PersonParty> people =
                personPartyRepository.findAll().stream().limit(MAX_CANDIDATES).toList();
        List<UUID> ids = people.stream().map(PersonParty::getPartyId).toList();
        SharedFacts facts = loadSharedFacts(ids);
        // FI-4 (#1135): individual addresses live on the person identity replica, keyed by the
        // people-contact person id, not the local party id.
        Map<UUID, ExtPersonReplica> personReplicas = personReplicasByPersonId(people);

        return people.stream()
                .map(person ->
                        toAttributes(person, facts, AddressSnapshot.of(personReplicas.get(person.getPersonId()))))
                .toList();
    }

    /**
     * The per-party facts both audiences need, batch-loaded once in a fixed query count.
     *
     * <p>Commercial and individual candidates differ only in the entity they start from and where
     * their address comes from; everything below is loaded identically for both.
     */
    private record SharedFacts(
            Map<UUID, CommunicationPreference> preferences,
            Map<UUID, Set<UUID>> tags,
            Map<UUID, List<ExtVehicle>> vehicles,
            Map<UUID, List<PartyVehicleLastServiceView>> lastServiceRows,
            Map<UUID, Instant> lastService,
            Map<UUID, Integer> intervalOverrides,
            Map<UUID, Instant> lastDeclined) {}

    private SharedFacts loadSharedFacts(List<UUID> ids) {
        Map<UUID, List<PartyVehicleLastServiceView>> lastServiceRows = lastServiceByPartyVehicle(ids);
        return new SharedFacts(
                preferencesByParty(ids),
                tagsByParty(ids),
                vehiclesByAccount(ids),
                lastServiceRows,
                latestByParty(lastServiceRows),
                intervalOverridesByVehicle(lastServiceRows),
                lastDeclinedByParty(ids));
    }

    /**
     * A party's structured postal address, or {@link #NONE} when none is on file.
     *
     * <p>One shape for both audiences, which read it from different replicas: commercial parties
     * from {@code ext_organization_postal_address}, individuals from the person replica (FI-4,
     * #1135). Collapsing the absent case here is what keeps four repeated null guards out of each
     * mapper.
     */
    private record AddressSnapshot(
            @Nullable String country,
            @Nullable String region,
            @Nullable String city,
            @Nullable String postalCode) {

        private static final AddressSnapshot NONE = new AddressSnapshot(null, null, null, null);

        static AddressSnapshot of(@Nullable ExtOrganizationPostalAddress address) {
            return address == null
                    ? NONE
                    : new AddressSnapshot(
                            address.getCountryCode(), address.getRegion(), address.getCity(), address.getPostalCode());
        }

        static AddressSnapshot of(@Nullable ExtPersonReplica replica) {
            return replica == null
                    ? NONE
                    : new AddressSnapshot(
                            replica.getAddressCountryCode(),
                            replica.getAddressRegion(),
                            replica.getAddressCity(),
                            replica.getAddressPostalCode());
        }
    }

    /**
     * The billing terms a segment can target, or {@link #NONE} for a party with no billing rules.
     *
     * <p>All three fields stay nullable: "no billing rules on file" and "tax exemption not yet
     * decided" are different states, and flattening the first to {@code false} would put
     * never-assessed accounts into a tax-exempt-is-false segment.
     */
    private record BillingSnapshot(
            @Nullable Boolean taxExempt,
            @Nullable Boolean creditHold,
            @Nullable String paymentTerms) {

        private static final BillingSnapshot NONE = new BillingSnapshot(null, null, null);

        static BillingSnapshot of(@Nullable BillingRulesEmbeddable rules) {
            return rules == null
                    ? NONE
                    : new BillingSnapshot(rules.getTaxExempt(), rules.getCreditHold(), rules.getPaymentTerms());
        }
    }

    /** The fleet attributes derived from a party's vehicles; empty for a party with none. */
    private record VehicleSnapshot(
            Set<String> makes, Set<String> models, Set<Integer> years, boolean anyActive, long count) {

        static VehicleSnapshot of(List<ExtVehicle> owned) {
            return new VehicleSnapshot(
                    distinct(owned, ExtVehicle::getMake),
                    distinct(owned, ExtVehicle::getModel),
                    owned.stream()
                            .map(ExtVehicle::getYear)
                            .filter(java.util.Objects::nonNull)
                            .collect(Collectors.toSet()),
                    owned.stream().anyMatch(ExtVehicle::isActive),
                    owned.size());
        }
    }

    private PartyAttributes toAttributes(CommercialParty account, SharedFacts facts, AddressSnapshot address) {
        UUID partyId = account.getPartyId();
        BillingSnapshot billing = BillingSnapshot.of(account.getBillingRules());
        return build(
                partyId,
                // A party type is always projected, even when the column is somehow unset: a
                // predicate on party.type compares strings, so a null there matches nothing at all
                // and would silently drop the account from every audience that names its type.
                java.util.Objects.requireNonNullElse(enumName(account.getPartyType()), DEFAULT_COMMERCIAL_TYPE),
                account.getParentParty() != null,
                account.getExternalIdentifiers() == null ? Map.of() : Map.copyOf(account.getExternalIdentifiers()),
                billing,
                commercialLabel(account),
                account,
                facts,
                address);
    }

    private PartyAttributes toAttributes(PersonParty person, SharedFacts facts, AddressSnapshot address) {
        return build(
                person.getPartyId(),
                INDIVIDUAL_TYPE,
                false,
                Map.of(),
                BillingSnapshot.NONE,
                // Person names live in pos-people-contact (ADR-0015); the customer number is the
                // only local label, and it is already non-identifying.
                person.getCustomerNumber(),
                person,
                facts,
                address);
    }

    private PartyAttributes build(
            UUID partyId,
            String partyType,
            boolean hasParentParty,
            Map<String, String> externalIdentifiers,
            BillingSnapshot billing,
            @Nullable String displayLabel,
            AbstractParty party,
            SharedFacts facts,
            AddressSnapshot address) {
        VehicleSnapshot fleet = VehicleSnapshot.of(facts.vehicles().getOrDefault(partyId, List.of()));
        CommunicationPreference preference = facts.preferences().get(partyId);
        List<PartyVehicleLastServiceView> serviceRows = facts.lastServiceRows().getOrDefault(partyId, List.of());
        return new PartyAttributes(
                partyId,
                partyType,
                enumName(party.getTier()),
                enumName(party.getStatus()),
                hasParentParty,
                externalIdentifiers,
                facts.tags().getOrDefault(partyId, Set.of()),
                billing.taxExempt(),
                billing.creditHold(),
                billing.paymentTerms(),
                consent(preference, true),
                consent(preference, false),
                fleet.makes(),
                fleet.models(),
                fleet.years(),
                fleet.anyActive(),
                fleet.count(),
                displayLabel,
                monthsSince(facts.lastService().get(partyId)),
                facts.lastService().containsKey(partyId),
                daysSince(facts.lastDeclined().get(partyId)),
                serviceDue(serviceRows, facts.intervalOverrides()),
                address.country(),
                address.region(),
                address.city(),
                address.postalCode());
    }

    /** The account's own display name, falling back to the legal name it was registered under. */
    private static @Nullable String commercialLabel(CommercialParty account) {
        return account.getDisplayName() != null ? account.getDisplayName() : account.getLegalName();
    }

    private static @Nullable String enumName(@Nullable Enum<?> value) {
        return value == null ? null : value.name();
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

    private Map<UUID, List<PartyVehicleLastServiceView>> lastServiceByPartyVehicle(List<UUID> partyIds) {
        if (partyIds.isEmpty()) {
            return Map.of();
        }
        return serviceHistoryRepository.findLastServiceByPartyAndVehicle(partyIds).stream()
                .filter(row -> row.getLastCompletedAt() != null)
                .collect(Collectors.groupingBy(PartyVehicleLastServiceView::getPartyId));
    }

    /** Party-level last completion: the max across the party's per-vehicle scopes. */
    private static Map<UUID, Instant> latestByParty(Map<UUID, List<PartyVehicleLastServiceView>> rowsByParty) {
        Map<UUID, Instant> byParty = new HashMap<>();
        rowsByParty.forEach((partyId, rows) -> rows.stream()
                .map(PartyVehicleLastServiceView::getLastCompletedAt)
                .max(Instant::compareTo)
                .ifPresent(last -> byParty.put(partyId, last)));
        return byParty;
    }

    /**
     * Per-vehicle interval overrides for every vehicle appearing in the candidates' service
     * history, batch-loaded from the {@code ext_vehicle_care_preference} replica in one query
     * (#1175). Vehicles without an override (or with a tombstoned one) are simply absent.
     */
    private Map<UUID, Integer> intervalOverridesByVehicle(Map<UUID, List<PartyVehicleLastServiceView>> rowsByParty) {
        Set<UUID> vehicleIds = rowsByParty.values().stream()
                .flatMap(List::stream)
                .map(PartyVehicleLastServiceView::getVehicleId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> byVehicle = new HashMap<>();
        extVehicleCarePreferenceRepository.findAllById(vehicleIds).forEach(preference -> {
            // A non-positive replica value can only be corrupt data; fall back to the default.
            if (preference.getServiceIntervalMonths() != null && preference.getServiceIntervalMonths() >= 1) {
                byVehicle.put(preference.getVehicleId(), preference.getServiceIntervalMonths());
            }
        });
        return byVehicle;
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

    /**
     * Care-preference interval elapsed (#1144, #1175): true when any of the party's service
     * scopes — a vehicle with history, or the vehicle-less scope — has its most recent completion
     * at least the effective interval old. The effective interval is the vehicle's replicated
     * care-preference override where one exists, the module-wide default otherwise (vehicle-less
     * history always uses the default). A party with no service history projects false —
     * never-served customers are targeted via {@code service.hasHistory} /
     * {@code service.monthsSinceLast}, not lumped into service-due reminders.
     */
    private boolean serviceDue(
            List<PartyVehicleLastServiceView> lastServiceRows, Map<UUID, Integer> intervalOverrides) {
        for (PartyVehicleLastServiceView row : lastServiceRows) {
            Integer months = monthsSince(row.getLastCompletedAt());
            if (months == null) {
                continue;
            }
            Integer override = row.getVehicleId() != null ? intervalOverrides.get(row.getVehicleId()) : null;
            int interval = override != null ? override : serviceDueMonths;
            if (months >= interval) {
                return true;
            }
        }
        return false;
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
