package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.TechnicianCredentialResponse;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ExtPersonCredentialReplica;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.Shop;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtPersonCredentialReplicaRepository;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.service.LocationHoursShiftWindowService.ShiftWindow;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Roster projections over the one person identity this module holds (CAP-328). Competence comes
 * from the {@code ext_person_credential} replica and is projected as credentials with a status,
 * never flattened to codes: an expired certification is listed as EXPIRED, not as a held skill.
 *
 * <p>The reference date for "held" is the facility's local date for a location roster
 * (DECISION-SHOPMGMT-015) and the clock's date for the location-less mechanic roster.
 *
 * <p>The location roster also carries a PLACEHOLDER shift window per entry (issue #2060): the
 * location's operating hours for the roster date, resolved once by {@link
 * LocationHoursShiftWindowService} and stamped unchanged on every technician, so the board can
 * render free hours until a real per-person schedule exists (#71).
 */
@Service
@RequiredArgsConstructor
public class MechanicRosterQueryServiceImpl implements MechanicRosterQueryService {

    private final MechanicRepository mechanicRepository;
    private final ExtPersonCredentialReplicaRepository credentialRepository;
    private final ShopRepository shopRepository;
    private final ExtLocationReplicaRepository locationReplicaRepository;
    private final LocationHoursParser hoursParser;
    private final LocationHoursShiftWindowService shiftWindowService;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<MechanicRosterEntryResponse> listMechanics(
            @Nullable MechanicStatus status, @Nullable String skillCode, @NonNull Pageable pageable) {
        MechanicStatus effectiveStatus = status == null ? MechanicStatus.ACTIVE : status;
        LocalDate onDate = LocalDate.now(clock);
        Page<Mechanic> mechanics =
                mechanicRepository.findRoster(effectiveStatus, skillFilter(skillCode), onDate, pageable);
        Map<UUID, List<TechnicianCredentialResponse>> credentialsByPerson =
                loadCredentials(mechanics.getContent(), onDate);
        return mechanics.map(mechanic -> MechanicRosterEntryResponse.builder()
                .mechanicId(mechanic.getMechanicId())
                .personId(mechanic.getPersonId())
                .firstName(mechanic.getFirstName())
                .lastName(mechanic.getLastName())
                .status(mechanic.getStatus())
                .hireDate(mechanic.getHireDate())
                .terminationDate(mechanic.getTerminationDate())
                .lastSyncedAt(mechanic.getLastSyncedAt())
                .credentials(credentialsByPerson.getOrDefault(mechanic.getPersonId(), List.of()))
                .build());
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull Page<LocationTechnicianRosterEntryResponse> listLocationTechnicians(
            @NonNull UUID locationId,
            @Nullable MechanicStatus status,
            @Nullable String skillCode,
            @Nullable LocalDate date,
            @NonNull Pageable pageable) {
        Shop shop = shopRepository
                .findById(locationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND"));
        MechanicStatus effectiveStatus = status == null ? MechanicStatus.ACTIVE : status;
        // One replica read serves both the facility date and the placeholder shift window.
        ExtLocationReplica location =
                locationReplicaRepository.findById(locationId).orElse(null);
        // AC6: an omitted date is today in the facility's own zone — not the server's, not UTC.
        LocalDate onDate =
                date != null ? date : LocalDate.now(clock.withZone(facilityZone(locationId, location, shop)));
        // AC7: resolved once per request and stamped on every entry, so the placeholder's "same
        // window for everyone" simplification is structural rather than incidental.
        ShiftWindow shiftWindow = shiftWindowService.resolve(locationId, location, onDate);
        Pageable fixedOrderPageable = pageable.isPaged()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
                : Pageable.unpaged();
        Page<Mechanic> mechanics = mechanicRepository.findRosterByLocation(
                locationId, effectiveStatus, skillFilter(skillCode), onDate, fixedOrderPageable);
        Map<UUID, List<TechnicianCredentialResponse>> credentialsByPerson =
                loadCredentials(mechanics.getContent(), onDate);
        return mechanics.map(mechanic -> LocationTechnicianRosterEntryResponse.builder()
                .locationId(locationId)
                .mechanicId(mechanic.getMechanicId())
                .personId(mechanic.getPersonId())
                .firstName(mechanic.getFirstName())
                .lastName(mechanic.getLastName())
                .status(mechanic.getStatus())
                .hireDate(mechanic.getHireDate())
                .terminationDate(mechanic.getTerminationDate())
                .lastSyncedAt(mechanic.getLastSyncedAt())
                .credentials(credentialsByPerson.getOrDefault(mechanic.getPersonId(), List.of()))
                .shiftStart(shiftWindow.start())
                .shiftEnd(shiftWindow.end())
                .shiftMinutes(shiftWindow.minutes())
                .shiftSource(shiftWindow.source())
                .shiftStatus(shiftWindow.status())
                .build());
    }

    /**
     * The facility's zone: the location replica's timezone (the owner's fact, CAP-326 D10), else
     * this module's own {@code Shop.timezone}, else UTC — the same fallback the schedule uses when
     * the owner has not said.
     */
    private ZoneId facilityZone(UUID locationId, @Nullable ExtLocationReplica location, Shop shop) {
        ZoneId fromReplica = Optional.ofNullable(location)
                .map(replica -> hoursParser.parseZone(locationId, replica.getTimezone()))
                .orElse(null);
        if (fromReplica != null) {
            return fromReplica;
        }
        ZoneId fromShop = hoursParser.parseZone(locationId, shop.getTimezone());
        return fromShop != null ? fromShop : ZoneOffset.UTC;
    }

    /**
     * The skill filter with a blank read as absent, so the query's {@code :skillCode IS NULL}
     * branch applies rather than a filter that matches nobody; normalized the way
     * {@link SkillRequirementResolver#normalize} reads every skill code (the repository
     * normalizes again, harmlessly, so a direct caller gets the same case-insensitive match).
     */
    private static @Nullable String skillFilter(@Nullable String skillCode) {
        String normalized = SkillRequirementResolver.normalize(skillCode);
        return normalized.isEmpty() ? null : normalized;
    }

    private Map<UUID, List<TechnicianCredentialResponse>> loadCredentials(List<Mechanic> mechanics, LocalDate onDate) {
        List<UUID> personIds = mechanics.stream()
                .map(Mechanic::getPersonId)
                .filter(Objects::nonNull)
                .toList();
        if (personIds.isEmpty()) {
            return Map.of();
        }
        return credentialRepository.findByPersonIdInOrderByIssuedOnDesc(personIds).stream()
                .collect(Collectors.groupingBy(
                        ExtPersonCredentialReplica::getPersonId,
                        Collectors.mapping(credential -> toCredential(credential, onDate), Collectors.toList())));
    }

    private static TechnicianCredentialResponse toCredential(ExtPersonCredentialReplica credential, LocalDate onDate) {
        return TechnicianCredentialResponse.builder()
                .credentialId(credential.getCredentialId())
                .skillCode(credential.getSkillCode())
                .competenceCode(credential.getCompetenceCode())
                .minGvwrClass(credential.getMinGvwrClass())
                .maxGvwrClass(credential.getMaxGvwrClass())
                .issuer(credential.getIssuer())
                .sourceCredentialCode(credential.getSourceCredentialCode())
                .issuedOn(credential.getIssuedOn())
                .expiresOn(credential.getExpiresOn())
                .proficiency(credential.getProficiency())
                .status(credential.statusOn(onDate))
                .build();
    }
}
