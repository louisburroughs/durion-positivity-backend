package com.positivity.shopmanager.internal.service;

import com.positivity.shopmanager.internal.dto.LocationTechnicianRosterEntryResponse;
import com.positivity.shopmanager.internal.dto.MechanicRosterEntryResponse;
import com.positivity.shopmanager.internal.entity.Mechanic;
import com.positivity.shopmanager.internal.entity.MechanicSkill;
import com.positivity.shopmanager.internal.entity.Technician;
import com.positivity.shopmanager.internal.enums.MechanicStatus;
import com.positivity.shopmanager.internal.repository.MechanicRepository;
import com.positivity.shopmanager.internal.repository.MechanicSkillRepository;
import com.positivity.shopmanager.internal.repository.ShopRepository;
import com.positivity.shopmanager.internal.repository.TechnicianRepository;
import java.util.List;
import java.util.Map;
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

@Service
@RequiredArgsConstructor
public class MechanicRosterQueryServiceImpl implements MechanicRosterQueryService {

  private final MechanicRepository mechanicRepository;
  private final MechanicSkillRepository mechanicSkillRepository;
  private final TechnicianRepository technicianRepository;
  private final ShopRepository shopRepository;

  @Override
  @Transactional(readOnly = true)
  public @NonNull Page<MechanicRosterEntryResponse> listMechanics(@Nullable MechanicStatus status,
      @Nullable String skillCode, @NonNull Pageable pageable) {
    MechanicStatus effectiveStatus = status == null ? MechanicStatus.ACTIVE : status;
    Page<Mechanic> mechanics = mechanicRepository.findRoster(effectiveStatus, skillCode, pageable);
    Map<UUID, List<String>> skillsByMechanicId = loadSkills(mechanics.getContent());
    return mechanics.map(mechanic -> toResponse(mechanic,
        skillsByMechanicId.getOrDefault(mechanic.getMechanicId(), List.of())));
  }

  @Override
  @Transactional(readOnly = true)
  public @NonNull Page<LocationTechnicianRosterEntryResponse> listLocationTechnicians(
      @NonNull UUID locationId, @Nullable MechanicStatus status, @Nullable String skillCode,
      @NonNull Pageable pageable) {
    if (!shopRepository.existsById(locationId)) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "SHOP_NOT_FOUND");
    }
    MechanicStatus effectiveStatus = status == null ? MechanicStatus.ACTIVE : status;
    Pageable fixedOrderPageable =
        pageable.isPaged() ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize())
            : Pageable.unpaged();
    Page<Technician> technicians = technicianRepository.findRosterByLocation(locationId,
        effectiveStatus, skillCode, fixedOrderPageable);
    Map<String, Mechanic> mechanicsByPersonId = loadMechanics(technicians.getContent());
    Map<UUID, List<String>> skillsByMechanicId =
        loadSkills(mechanicsByPersonId.values().stream().toList());
    return technicians.map(technician -> toLocationResponse(locationId, technician,
        mechanicsByPersonId.get(technician.getPersonId().toString()), skillsByMechanicId));
  }

  private Map<UUID, List<String>> loadSkills(List<Mechanic> mechanics) {
    List<UUID> mechanicIds = mechanics.stream().map(Mechanic::getMechanicId).toList();
    if (mechanicIds.isEmpty()) {
      return Map.of();
    }
    return mechanicSkillRepository.findAllByMechanicIdIn(mechanicIds).stream()
        .collect(Collectors.groupingBy(MechanicSkill::getMechanicId,
            Collectors.mapping(MechanicSkill::getSkillCode, Collectors.toList())));
  }

  private Map<String, Mechanic> loadMechanics(List<Technician> technicians) {
    List<String> personIds =
        technicians.stream().map(Technician::getPersonId).map(UUID::toString).toList();
    if (personIds.isEmpty()) {
      return Map.of();
    }
    return mechanicRepository.findAllByPersonIdIn(personIds).stream()
        .collect(Collectors.toMap(Mechanic::getPersonId, mechanic -> mechanic));
  }

  private MechanicRosterEntryResponse toResponse(Mechanic mechanic, List<String> skills) {
    return MechanicRosterEntryResponse.builder().mechanicId(mechanic.getMechanicId())
        .personId(UUID.fromString(mechanic.getPersonId())).firstName(mechanic.getFirstName())
        .lastName(mechanic.getLastName()).status(mechanic.getStatus())
        .hireDate(mechanic.getHireDate()).terminationDate(mechanic.getTerminationDate())
        .lastSyncedAt(mechanic.getLastSyncedAt()).skills(skills).build();
  }

  private LocationTechnicianRosterEntryResponse toLocationResponse(UUID locationId,
      Technician technician, Mechanic mechanic, Map<UUID, List<String>> skillsByMechanicId) {
    if (mechanic == null) {
      throw new IllegalStateException(
          "Roster query returned a technician without a matching mechanic");
    }
    return LocationTechnicianRosterEntryResponse.builder().technicianId(technician.getId())
        .locationId(locationId).mechanicId(mechanic.getMechanicId())
        .personId(technician.getPersonId()).firstName(mechanic.getFirstName())
        .lastName(mechanic.getLastName()).status(mechanic.getStatus())
        .hireDate(mechanic.getHireDate()).terminationDate(mechanic.getTerminationDate())
        .lastSyncedAt(mechanic.getLastSyncedAt())
        .skills(skillsByMechanicId.getOrDefault(mechanic.getMechanicId(), List.of())).build();
  }
}
