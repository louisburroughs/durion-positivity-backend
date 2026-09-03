package com.positivity.shopmanager.internal.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.positivity.shopmanager.internal.dto.ShopDashboardResponse;
import com.positivity.shopmanager.internal.dto.ShopDashboardUnit;
import com.positivity.shopmanager.internal.dto.ShopDashboardVehicle;
import com.positivity.shopmanager.internal.dto.ShopDashboardWorkorder;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtMobileUnitReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ExtVehicleReplica;
import com.positivity.shopmanager.internal.entity.ExtWorkorderReplica;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.ShopDashboardUnitType;
import com.positivity.shopmanager.internal.enums.WorkorderStatusMirror;
import com.positivity.shopmanager.internal.exception.LocationNotFoundException;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtVehicleReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtWorkorderReplicaRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assembles {@code GET /v1/shop-dashboard} from this module's local replicas (#1658).
 *
 * <h2>Query budget</h2>
 *
 * The whole response costs a <em>fixed</em> number of statements — it does not grow with the
 * number of units or workorders at the location, which is the hard requirement behind AC13:
 *
 * <ol>
 *   <li>the shop row, for existence and timezone;
 *   <li>the active bays at the location;
 *   <li>the active mobile units based at the location;
 *   <li>the open workorders at the location, already ordered and capped in SQL;
 *   <li>every referenced vehicle, by id, in one batch;
 *   <li>every referenced technician in the people-contact replica, by id, in one batch;
 *   <li>the same ids against the HR mechanic projection, in one batch, as the name fallback.
 * </ol>
 *
 * <p>Nothing is looked up per unit or per workorder. {@code ShopDashboardServiceTest} pins this
 * with a Hibernate {@code Statistics} statement count that must not move when the data set grows.
 *
 * <h2>Where the facts come from</h2>
 *
 * Every source above is a local table. None of them is a live call into another domain, which
 * ADR-0044 R1 forbids. That is what lets the "one batched read" requirement and the "no new
 * synchronous workexec endpoint" requirement both hold at once: the batching happens against
 * {@code ext_workorder}, not against pos-workorder.
 *
 * <p>Mechanic names resolve from the two People replicas this module already runs — the
 * people-contact person replica that backs {@code TechnicianController} first, then the HR mechanic
 * projection that backs {@code MechanicRosterController}. Both are batched; neither is a third
 * cross-domain dependency.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopDashboardServiceImpl implements ShopDashboardService {

    /**
     * Maximum {@code openWorkorders} rows returned (AC5). The query asks for one more than this so
     * "there are more" is a fact from the database, not a guess from a full count.
     */
    static final int OPEN_WORKORDER_CAP = 200;

    private static final ObjectMapper MECHANIC_IDS_MAPPER = JsonMapper.builder().build();

    /**
     * Presentation order of the status bands (AC4). Blocked work is first because it is what needs
     * a human; ready-for-pickup is last because it needs only a phone call.
     */
    private static final Map<String, Integer> STATUS_BAND = Map.of(
            "AWAITING_PARTS", 0,
            "AWAITING_APPROVAL", 0,
            "DRAFT", 1,
            "APPROVED", 1,
            "ASSIGNED", 1,
            "WORK_IN_PROGRESS", 2,
            "READY_FOR_PICKUP", 3);

    private static final int UNKNOWN_STATUS_BAND = 4;

    private final Clock clock;
    private final ShopRepository shopRepository;
    private final ExtBayReplicaRepository extBayReplicaRepository;
    private final ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;
    private final ExtWorkorderReplicaRepository extWorkorderReplicaRepository;
    private final ExtVehicleReplicaRepository extVehicleReplicaRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final MechanicRepository mechanicRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull ShopDashboardResponse getDashboard(@NonNull UUID locationId, @Nullable LocalDate date) {
        Shop shop = shopRepository.findById(locationId).orElseThrow(() -> new LocationNotFoundException(locationId));
        LocalDate asOf = date != null ? date : localToday(shop);

        List<ExtBayReplica> bays = extBayReplicaRepository.findActiveByLocationOrdered(locationId);
        List<ExtMobileUnitReplica> mobileUnits =
                extMobileUnitReplicaRepository.findActiveByBaseLocationOrdered(locationId);

        // One row over the cap: if the extra row came back, there is more work than we will show.
        List<ExtWorkorderReplica> openRows = extWorkorderReplicaRepository.findOpenAtLocation(
                locationId, WorkorderStatusMirror.TERMINAL_STATUSES, PageRequest.of(0, OPEN_WORKORDER_CAP + 1));
        boolean truncated = openRows.size() > OPEN_WORKORDER_CAP;
        if (truncated) {
            openRows = openRows.subList(0, OPEN_WORKORDER_CAP);
        }

        Map<UUID, String> unitNames = new HashMap<>();
        bays.forEach(bay -> unitNames.put(bay.getBayId(), bay.getName()));
        mobileUnits.forEach(unit -> unitNames.put(unit.getMobileUnitId(), unit.getName()));

        // Occupancy is queried against the units, not filtered out of openRows: openRows is cut at
        // the cap, and a unit whose workorder fell past the cut would then read as free. It is the
        // one view the date scopes; the openWorkorders list above deliberately is not (AC2).
        Map<UUID, ExtWorkorderReplica> occupancy = unitNames.isEmpty()
                ? Map.of()
                : resolveOccupancy(
                        extWorkorderReplicaRepository.findOpenHoldingResources(
                                locationId, WorkorderStatusMirror.TERMINAL_STATUSES, unitNames.keySet()),
                        asOf);

        // Enrichment covers both lists at once: an occupying workorder past the cap is not in
        // openRows, and its vehicle and technicians still have to resolve.
        List<ExtWorkorderReplica> enrichmentRows = Stream.concat(openRows.stream(), occupancy.values().stream())
                .collect(Collectors.toMap(ExtWorkorderReplica::getWorkorderId, row -> row, (a, b) -> a))
                .values()
                .stream()
                .toList();
        Map<UUID, ShopDashboardVehicle> vehicles = loadVehicles(enrichmentRows);
        Map<UUID, String> mechanicNames = loadMechanicNames(enrichmentRows);

        List<ShopDashboardUnit> units = new ArrayList<>(bays.size() + mobileUnits.size());
        for (ExtBayReplica bay : bays) {
            units.add(new ShopDashboardUnit(
                    bay.getBayId(),
                    ShopDashboardUnitType.BAY,
                    bay.getName(),
                    toRow(occupancy.get(bay.getBayId()), unitNames, vehicles, mechanicNames)));
        }
        for (ExtMobileUnitReplica unit : mobileUnits) {
            units.add(new ShopDashboardUnit(
                    unit.getMobileUnitId(),
                    ShopDashboardUnitType.MOBILE_UNIT,
                    unit.getName(),
                    toRow(occupancy.get(unit.getMobileUnitId()), unitNames, vehicles, mechanicNames)));
        }

        List<ShopDashboardWorkorder> openWorkorders = openRows.stream()
                .map(row -> toRow(row, unitNames, vehicles, mechanicNames))
                .toList();

        return new ShopDashboardResponse(locationId, asOf, List.copyOf(units), openWorkorders, truncated);
    }

    /**
     * Today in the location's own calendar (AC2). A shop that has not recorded a timezone falls
     * back to UTC rather than to the server's zone: the server zone is an accident of deployment,
     * and a dashboard that silently rolls over at a different hour per pod is worse than one that
     * rolls over at a documented, wrong-but-stable one.
     */
    private LocalDate localToday(Shop shop) {
        String timezone = shop.getTimezone();
        if (timezone == null || timezone.isBlank()) {
            return LocalDate.now(clock.withZone(ZoneOffset.UTC));
        }
        try {
            return LocalDate.now(clock.withZone(ZoneId.of(timezone)));
        } catch (DateTimeException e) {
            log.warn(
                    "Shop {} has unrecognised timezone '{}'; defaulting the dashboard date to UTC",
                    shop.getId(),
                    timezone);
            return LocalDate.now(clock.withZone(ZoneOffset.UTC));
        }
    }

    /**
     * Which workorder occupies each unit as of {@code asOf}, keyed by unit id.
     *
     * <p>A row occupies its unit when it carries no scheduled date — work that is simply happening
     * — or is scheduled <em>on or before</em> the requested day. That is the entire meaning of the
     * {@code date} parameter.
     *
     * <p>The bound is an upper bound, not an equality, and both halves of that are deliberate
     * (#1658 review, mirroring pos-workorder's {@code findOpenResourceHoldersAtLocation} in #1656).
     * An equality check reported a still-open multi-day job scheduled yesterday as having freed its
     * bay today, while the same response's {@code openWorkorders} went on naming that bay as its
     * unit — one payload contradicting itself. Leaving the bound off entirely would trade that
     * false-free for a false-occupied: tomorrow's booking would black out a bay that is empty all
     * of today. Work scheduled for a future day is booked, not occupying the unit now.
     *
     * <p>Nothing here consults a "free/occupied" flag, because none exists and none should: the
     * candidate set is the open-workorder set, so a COMPLETED or CANCELLED job has already been
     * filtered out upstream and its unit reads as free with no write on either side of the domain
     * wall (AC6). By the same token READY_FOR_PICKUP is open, so its unit stays occupied (AC7).
     *
     * <p>Two open workorders pointing at one unit is a real operational conflict, not something to
     * hide: the more urgent one is shown, ordered by the same band rule as the list, so the board
     * surfaces the blocked job rather than an arbitrary one.
     */
    private Map<UUID, ExtWorkorderReplica> resolveOccupancy(List<ExtWorkorderReplica> candidates, LocalDate asOf) {
        Comparator<ExtWorkorderReplica> byUrgency = Comparator.comparingInt(
                        (ExtWorkorderReplica row) -> band(row.getStatus()))
                .thenComparing(ExtWorkorderReplica::getWorkorderNumber, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ExtWorkorderReplica::getWorkorderId);

        Map<UUID, ExtWorkorderReplica> occupancy = new HashMap<>();
        for (ExtWorkorderReplica row : candidates) {
            UUID resourceId = row.getResourceId();
            if (resourceId == null) {
                continue;
            }
            if (row.getScheduledDate() != null && row.getScheduledDate().isAfter(asOf)) {
                continue;
            }
            occupancy.merge(resourceId, row, (a, b) -> byUrgency.compare(a, b) <= 0 ? a : b);
        }
        return occupancy;
    }

    private int band(@Nullable String status) {
        return status == null ? UNKNOWN_STATUS_BAND : STATUS_BAND.getOrDefault(status, UNKNOWN_STATUS_BAND);
    }

    /** Every referenced vehicle in one batched read — never one call per workorder. */
    private Map<UUID, ShopDashboardVehicle> loadVehicles(List<ExtWorkorderReplica> rows) {
        Set<UUID> vehicleIds = rows.stream()
                .map(ExtWorkorderReplica::getVehicleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (vehicleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ShopDashboardVehicle> vehicles = new HashMap<>();
        for (ExtVehicleReplica replica : extVehicleReplicaRepository.findAllById(vehicleIds)) {
            vehicles.put(
                    replica.getVehicleId(),
                    new ShopDashboardVehicle(
                            replica.getVehicleId(),
                            replica.getVin(),
                            replica.getYear(),
                            replica.getMake(),
                            replica.getModel()));
        }
        return vehicles;
    }

    /**
     * Display names for every technician referenced anywhere in the response, in two batched reads.
     *
     * <p>The people-contact replica wins where it has a row — it is the same source
     * {@code TechnicianController} answers from, so the dashboard and the technician detail page
     * cannot disagree about a person's name. The HR mechanic projection behind
     * {@code MechanicRosterController} is the fallback, which matters in practice because staffing
     * facts and contact facts arrive on different topics and one can be ahead of the other.
     */
    private Map<UUID, String> loadMechanicNames(List<ExtWorkorderReplica> rows) {
        Set<UUID> personIds = rows.stream()
                .flatMap(row -> parseMechanicIds(row.getMechanicIds()).stream())
                .collect(Collectors.toSet());
        if (personIds.isEmpty()) {
            return Map.of();
        }

        Map<UUID, String> names = new HashMap<>();
        for (Mechanic mechanic : mechanicRepository.findAllByPersonIdIn(
                personIds.stream().map(UUID::toString).toList())) {
            String name = displayName(mechanic.getFirstName(), mechanic.getLastName());
            if (name != null) {
                try {
                    names.put(UUID.fromString(mechanic.getPersonId()), name);
                } catch (IllegalArgumentException e) {
                    log.debug(
                            "Mechanic {} has a non-UUID personId; skipped for name resolution", mechanic.getPersonId());
                }
            }
        }
        for (ExtPersonReplica person : extPersonReplicaRepository.findAllById(personIds)) {
            String name = displayName(person.getFirstName(), person.getLastName());
            if (name != null) {
                names.put(person.getPersonId(), name);
            }
        }
        return names;
    }

    private @Nullable String displayName(@Nullable String firstName, @Nullable String lastName) {
        String name = Stream.of(firstName, lastName)
                .filter(part -> part != null && !part.isBlank())
                .reduce((a, b) -> a + " " + b)
                .orElse(null);
        return name == null || name.isBlank() ? null : name;
    }

    private @Nullable ShopDashboardWorkorder toRow(
            @Nullable ExtWorkorderReplica row,
            Map<UUID, String> unitNames,
            Map<UUID, ShopDashboardVehicle> vehicles,
            Map<UUID, String> mechanicNames) {
        if (row == null) {
            return null;
        }
        List<UUID> mechanicIds = parseMechanicIds(row.getMechanicIds());
        List<String> names = mechanicIds.stream()
                .map(id -> mechanicNames.getOrDefault(id, null))
                .filter(Objects::nonNull)
                .toList();
        // mechanicName is the name of the FIRST assigned technician, resolved by id — never the
        // first name that happens to resolve (#1658 review). names above has the unresolvable
        // technicians squeezed out of it, so names.get(0) would silently attribute the job to
        // whoever came next whenever the lead technician's person replica is lagging. A wrong
        // technician on the board is worse than a blank one, so a missing replica row yields null
        // and the assignment keeps its identity in the workorder's own mechanic ids.
        String leadMechanicName = mechanicIds.isEmpty() ? null : mechanicNames.get(mechanicIds.get(0));
        UUID resourceId = row.getResourceId();
        return new ShopDashboardWorkorder(
                row.getWorkorderId(),
                row.getWorkorderNumber(),
                row.getStatus(),
                resourceId,
                resourceId == null ? null : unitNames.get(resourceId),
                resourceId == null ? null : parseUnitType(row.getResourceType()),
                row.getVehicleId() == null ? null : vehicles.get(row.getVehicleId()),
                leadMechanicName,
                names,
                row.getPromisedAt());
    }

    private @Nullable ShopDashboardUnitType parseUnitType(@Nullable String resourceType) {
        if (resourceType == null || resourceType.isBlank()) {
            return null;
        }
        try {
            return ShopDashboardUnitType.valueOf(resourceType);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown replicated resourceType '{}'; rendering the unit kind as unknown", resourceType);
            return null;
        }
    }

    /** Reads the replicated JSON array of technician ids; a malformed snapshot degrades to none. */
    private List<UUID> parseMechanicIds(@Nullable String mechanicIdsJson) {
        if (mechanicIdsJson == null || mechanicIdsJson.isBlank()) {
            return List.of();
        }
        List<String> raw;
        try {
            raw = MECHANIC_IDS_MAPPER.readValue(mechanicIdsJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("Unreadable mechanic_ids snapshot in ext_workorder: {}", mechanicIdsJson);
            return List.of();
        }
        Map<UUID, Boolean> distinct = new LinkedHashMap<>();
        for (String candidate : raw) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            try {
                distinct.putIfAbsent(UUID.fromString(candidate.trim()), Boolean.TRUE);
            } catch (IllegalArgumentException e) {
                log.debug("Skipping non-UUID mechanic id '{}' in ext_workorder snapshot", candidate);
            }
        }
        return List.copyOf(distinct.keySet());
    }
}
