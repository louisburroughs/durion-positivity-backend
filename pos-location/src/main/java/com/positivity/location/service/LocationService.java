package com.positivity.location.service;

import com.positivity.location.internal.client.PersonClient;
import com.positivity.location.internal.dto.LocationPatchRequest;
import com.positivity.location.internal.dto.LocationParentResponseDTO;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.dto.LocationTypeDTO;
import com.positivity.location.internal.dto.LocationValidationResponseDTO;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.LocationTypeRepository;
import com.positivity.location.internal.dto.PersonDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {
    private static final String STATUS_ACTIVE = "ACTIVE";
    private static final String STATUS_INACTIVE = "INACTIVE";
    private static final String INVALID_TIMEZONE = "INVALID_TIMEZONE";
    private static final String INVALID_OPERATING_HOURS = "INVALID_OPERATING_HOURS";

    private final LocationRepository locationRepository;
    private final LocationParentRepository locationParentRepository;
    private final LocationTypeRepository locationTypeRepository;
    private final PersonClient personClient;

    // Issue CAP-136 #78: lightweight process-level guard used with repository
    // checks.
    private final Set<String> recentNormalizedNames = new HashSet<>();

    @Transactional(readOnly = true)
    public List<LocationResponseDTO> getAllLocationsDto() {
        return locationRepository.findAll().stream()
                .map(this::toLocationResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<LocationResponseDTO> getLocationByIdDto(UUID id) {
        return locationRepository.findById(id).map(this::toLocationResponse);
    }

    /**
     * Returns locations for a status filter, defaulting to ACTIVE when blank.
     *
     * Issue: CAP-136 #78
     *
     * @param status   optional status filter
     * @param pageable paging input
     * @return paged locations
     */
    @Transactional(readOnly = true)
    public Page<LocationResponseDTO> listLocations(String status, Pageable pageable) {
        String statusFilter = normalizeStatus(status);
        return locationRepository.findByStatus(statusFilter, pageable).map(this::toLocationResponse);
    }

    @Transactional(readOnly = true)
    public LocationValidationResponseDTO getLocationValidation(UUID id) {
        return locationRepository.findById(id)
                .map(location -> LocationValidationResponseDTO.builder()
                        .locationId(id)
                        .exists(true)
                        .active(location.isActive())
                        .build())
                .orElseGet(() -> LocationValidationResponseDTO.builder()
                        .locationId(id)
                        .exists(false)
                        .active(false)
                        .build());
    }

    @Transactional
    public LocationResponseDTO createLocation(LocationRequestDTO request) {
        validateTimezone(request.getTimezone());
        validateOperatingHours(request.getOperatingHours());
        String normalizedName = normalizeName(request.getName());
        // Issue CAP-136 #78: conflict on duplicate name.
        if (locationRepository.existsByNormalizedName(normalizedName)
                || recentNormalizedNames.contains(normalizedName)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN");
        }

        Location location = new Location();
        applyLocationRequest(location, request);
        Location saved = saveLocationInternal(location);
        recentNormalizedNames.add(normalizedName);
        return toLocationResponse(saved);
    }

    @Transactional
    public Optional<LocationResponseDTO> updateLocation(UUID id, LocationRequestDTO request) {
        Optional<Location> existingLocation = locationRepository.findById(id);
        if (existingLocation.isEmpty()) {
            return Optional.empty();
        }
        Location location = existingLocation.get();
        validateTimezone(request.getTimezone());
        validateOperatingHours(request.getOperatingHours());
        String normalizedName = normalizeName(request.getName());
        if (locationRepository.findByNormalizedNameAndIdNot(normalizedName, id).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN");
        }
        applyLocationRequest(location, request);
        Location updated = saveLocationInternal(location);
        recentNormalizedNames.add(normalizedName);
        return Optional.of(toLocationResponse(updated));
    }

    /**
     * Applies partial location updates including deactivation and rename conflict
     * checks.
     *
     * Issue: CAP-136 #78
     *
     * @param id    location id
     * @param patch partial payload
     * @return updated location response
     */
    @Transactional
    public LocationResponseDTO patchLocation(UUID id, LocationPatchRequest patch) {
        Location location = locationRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (patch.getName() != null && !patch.getName().isBlank()) {
            String normalizedName = normalizeName(patch.getName());
            if (locationRepository.findByNormalizedNameAndIdNot(normalizedName, id).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "LOCATION_NAME_TAKEN");
            }
            location.setName(patch.getName());
            recentNormalizedNames.add(normalizedName);
        }

        if (patch.getTimezone() != null && !patch.getTimezone().isBlank()) {
            validateTimezone(patch.getTimezone());
            location.setTimezone(patch.getTimezone());
        }

        if (patch.getOperatingHours() != null) {
            validateOperatingHours(patch.getOperatingHours());
            location.setOperatingHours(String.valueOf(patch.getOperatingHours()));
        }

        if (patch.getHolidayClosures() != null) {
            location.setHolidayClosures(String.valueOf(patch.getHolidayClosures()));
        }

        if (patch.getCheckInBufferMinutes() != null) {
            location.setCheckInBufferMinutes(patch.getCheckInBufferMinutes());
        }

        if (patch.getCleanupBufferMinutes() != null) {
            location.setCleanupBufferMinutes(patch.getCleanupBufferMinutes());
        }

        if (patch.getStatus() != null && patch.getStatus().equalsIgnoreCase(STATUS_INACTIVE)) {
            location.setStatus(STATUS_INACTIVE);
            location.setActive(false);
        }

        return toLocationResponse(saveLocationInternal(location));
    }

    @Transactional
    public LocationParentResponseDTO addParent(UUID childId, UUID parentId, String parentTypeValue) {
        ParentType parentType = toParentType(parentTypeValue);
        return toLocationParentResponse(addParentInternal(childId, parentId, parentType));
    }

    @Transactional(readOnly = true)
    public List<LocationParentResponseDTO> getAllParentsDto() {
        return locationParentRepository.findAll().stream()
                .map(this::toLocationParentResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<LocationResponseDTO> getAllChildrenDto(UUID parentId, String parentTypeValue) {
        ParentType parentType = null;
        if (parentTypeValue != null && !parentTypeValue.isBlank()) {
            parentType = toParentType(parentTypeValue);
        }
        return findChildren(parentId, parentType).stream().map(this::toLocationResponse).toList();
    }

    public List<Location> getAllLocations() {
        return locationRepository.findAll();
    }

    public Optional<Location> getLocationById(UUID id) {
        return locationRepository.findById(id);
    }

    @Transactional
    public Location saveLocation(Location location) {
        return saveLocationInternal(location);
    }

    private Location saveLocationInternal(Location location) {
        try {
            return locationRepository.saveAndFlush(location);
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "OPTIMISTIC_LOCK_FAILED", e);
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Location code already exists", e);
        }
    }

    public void deleteLocation(UUID id) {
        locationRepository.deleteById(id);
    }

    @Transactional
    public LocationParent addParent(UUID childId, UUID parentId, ParentType parentType) {
        return addParentInternal(childId, parentId, parentType);
    }

    private LocationParent addParentInternal(UUID childId, UUID parentId, ParentType parentType) {
        if (childId.equals(parentId)) {
            throw new IllegalArgumentException("A location cannot be its own parent");
        }

        // Lock both rows in deterministic order to prevent race conditions when two
        // requests attempt inverse links concurrently.
        Location lockedFirst;
        Location lockedSecond;
        if (childId.compareTo(parentId) < 0) {
            lockedFirst = locationRepository.findByIdForUpdate(childId).orElseThrow();
            lockedSecond = locationRepository.findByIdForUpdate(parentId).orElseThrow();
        } else {
            lockedFirst = locationRepository.findByIdForUpdate(parentId).orElseThrow();
            lockedSecond = locationRepository.findByIdForUpdate(childId).orElseThrow();
        }

        Location child = childId.equals(lockedFirst.getId()) ? lockedFirst : lockedSecond;
        Location parent = parentId.equals(lockedFirst.getId()) ? lockedFirst : lockedSecond;

        if (locationParentRepository.existsByChild_IdAndParentType(childId, parentType)) {
            throw new IllegalStateException("Location already has a parent for parentType " + parentType);
        }
        if (locationParentRepository.existsByChild_IdAndParent_Id(childId, parentId)) {
            throw new IllegalStateException("Parent relationship already exists");
        }
        if (locationParentRepository.existsByChild_IdAndParent_Id(parentId, childId)) {
            throw new IllegalStateException("Circular relationship detected: inverse relationship already exists");
        }
        if (isDescendant(childId, parentId)) {
            throw new IllegalStateException("Circular relationship detected: parent is a descendant of child");
        }

        LocationParent locationParent = LocationParent.builder()
                .child(child)
                .parent(parent)
                .parentType(parentType)
                .build();
        LocationParent saved = locationParentRepository.saveAndFlush(locationParent);

        // Post-persist defensive validation for edge races across nodes.
        if (locationParentRepository.existsByChild_IdAndParent_Id(parentId, childId)) {
            throw new IllegalStateException("Circular relationship detected after save");
        }
        return saved;
    }

    @Transactional(readOnly = true)
    public List<LocationParent> getAllParents() {
        return locationParentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Location> getAllChildren(UUID parentId) {
        return findChildren(parentId, null);
    }

    @Transactional(readOnly = true)
    public List<Location> getAllChildren(UUID parentId, ParentType parentType) {
        return findChildren(parentId, parentType);
    }

    public PersonDTO getResponsiblePerson(UUID locationId) {
        Location location = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (location.getResponsiblePersonId() == null)
            return null;
        return personClient.getPersonById(location.getResponsiblePersonId());
    }

    private boolean isDescendant(UUID ancestorId, UUID targetDescendantId) {
        return locationParentRepository.isDescendant(ancestorId, targetDescendantId);
    }

    private List<Location> findChildren(UUID parentId, ParentType parentType) {
        if (parentType == null) {
            return locationParentRepository.findByParent_Id(parentId).stream()
                    .map(LocationParent::getChild)
                    .toList();
        }
        return locationParentRepository.findByParent_IdAndParentType(parentId, parentType).stream()
                .map(LocationParent::getChild)
                .toList();
    }

    private ParentType toParentType(String value) {
        try {
            return ParentType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid parentType: " + value, exception);
        }
    }

    private void applyLocationRequest(Location location, LocationRequestDTO request) {
        location.setName(request.getName());
        location.setCode(request.getCode());
        location.setStatus(request.getActive() != null && !request.getActive() ? STATUS_INACTIVE : STATUS_ACTIVE);
        location.setTimezone(request.getTimezone());
        if (request.getOperatingHours() != null) {
            location.setOperatingHours(String.valueOf(request.getOperatingHours()));
        }
        if (request.getHolidayClosures() != null) {
            location.setHolidayClosures(String.valueOf(request.getHolidayClosures()));
        }
        location.setCheckInBufferMinutes(request.getCheckInBufferMinutes());
        location.setCleanupBufferMinutes(request.getCleanupBufferMinutes());
        location.setGeographicalLocationId(request.getGeographicalLocationId());
        location.setAddressLine1(request.getAddressLine1());
        location.setAddressLine2(request.getAddressLine2());
        location.setCity(request.getCity());
        location.setState(request.getState());
        location.setPostalCode(request.getPostalCode());
        location.setCountry(request.getCountry());
        location.setMailingAddress(request.getMailingAddress());
        if (request.getActive() != null) {
            location.setActive(request.getActive());
        } else if (location.getId() == null) {
            location.setActive(true);
        }
        location.setResponsiblePersonId(request.getResponsiblePersonId());
        location.setType(resolveLocationType(request.getType()));
    }

    private void validateTimezone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return;
        }
        try {
            ZoneId.of(timezone);
        } catch (DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_TIMEZONE);
        }
    }

    private void validateOperatingHours(
            List<com.positivity.location.internal.dto.OperatingHoursRequest> operatingHours) {
        if (operatingHours == null) {
            return;
        }
        Set<String> days = new HashSet<>();
        for (var hours : operatingHours) {
            if (!days.add(hours.getDayOfWeek())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_OPERATING_HOURS);
            }
            if (hours.getOpenTime() == null || hours.getCloseTime() == null
                    || !hours.getOpenTime().isBefore(hours.getCloseTime())) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, INVALID_OPERATING_HOURS);
            }
        }
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return STATUS_ACTIVE;
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private LocationType resolveLocationType(LocationTypeDTO locationTypeDto) {
        if (locationTypeDto == null) {
            return null;
        }

        if (locationTypeDto.getId() != null) {
            return locationTypeRepository.findById(locationTypeDto.getId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Unknown location type id: " + locationTypeDto.getId()));
        }

        if (locationTypeDto.getName() == null || locationTypeDto.getName().isBlank()) {
            return null;
        }

        return locationTypeRepository.findByNameIgnoreCase(locationTypeDto.getName())
                .orElseGet(() -> locationTypeRepository.save(LocationType.builder()
                        .name(locationTypeDto.getName())
                        .description(locationTypeDto.getDescription())
                        .build()));
    }

    private LocationResponseDTO toLocationResponse(Location location) {
        return LocationResponseDTO.builder()
                .id(location.getId())
                .name(location.getName())
                .code(location.getCode())
                .geographicalLocationId(location.getGeographicalLocationId())
                .addressLine1(location.getAddressLine1())
                .addressLine2(location.getAddressLine2())
                .city(location.getCity())
                .state(location.getState())
                .postalCode(location.getPostalCode())
                .country(location.getCountry())
                .mailingAddress(location.getMailingAddress())
                .active(location.isActive())
                .responsiblePersonId(location.getResponsiblePersonId())
                .type(toLocationTypeDto(location.getType()))
                .build();
    }

    private LocationTypeDTO toLocationTypeDto(LocationType locationType) {
        if (locationType == null) {
            return null;
        }
        return LocationTypeDTO.builder()
                .id(locationType.getId())
                .name(locationType.getName())
                .description(locationType.getDescription())
                .build();
    }

    private LocationParentResponseDTO toLocationParentResponse(LocationParent locationParent) {
        return LocationParentResponseDTO.builder()
                .id(locationParent.getId())
                .parentId(locationParent.getParent() != null ? locationParent.getParent().getId() : null)
                .childId(locationParent.getChild() != null ? locationParent.getChild().getId() : null)
                .parentType(locationParent.getParentType() != null ? locationParent.getParentType().name() : null)
                .build();
    }
}
