package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.CreateLocationRequest;
import com.positivity.location.internal.dto.LocationDto;
import com.positivity.location.internal.dto.UpdateLocationRequest;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.exception.GeographicalLocationNotFoundException;
import com.positivity.location.internal.exception.LocationNotFoundException;
import com.positivity.location.internal.exception.LocationTypeNotFoundException;
import com.positivity.location.internal.repository.GeographicalLocationRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.LocationTypeRepository;
import com.positivity.location.service.LocationService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumMap;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class LocationServiceImpl implements LocationService {

    private final LocationRepository locationRepository;
    private final LocationTypeRepository locationTypeRepository;
    private final GeographicalLocationRepository geographicalLocationRepository;

    public LocationServiceImpl(
            @NonNull LocationRepository locationRepository,
            @NonNull LocationTypeRepository locationTypeRepository,
            @NonNull GeographicalLocationRepository geographicalLocationRepository) {
        this.locationRepository = locationRepository;
        this.locationTypeRepository = locationTypeRepository;
        this.geographicalLocationRepository = geographicalLocationRepository;
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<LocationDto> listLocations() {
        return locationRepository.findAll().stream()
                .map(this::toDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public LocationDto getLocationById(@NonNull UUID locationId) {
        return toDto(getLocationEntity(locationId));
    }

    @Override
    @NonNull
    public LocationDto createLocation(@NonNull CreateLocationRequest request) {
        LocationType locationType = getLocationType(request.getTypeId());
        validateGeographicalLocation(request.getGeographicalLocationId());

        Location location = new Location();
        location.setName(request.getName().trim());
        location.setType(locationType);
        location.setGeographicalLocationId(request.getGeographicalLocationId());

        if (request.getParents() != null) {
            location.setParents(new EnumMap<>(request.getParents()));
        }

        return toDto(locationRepository.save(location));
    }

    @Override
    @NonNull
    public LocationDto updateLocation(@NonNull UUID locationId, @NonNull UpdateLocationRequest request) {
        Location location = getLocationEntity(locationId);
        LocationType locationType = getLocationType(request.getTypeId());
        validateGeographicalLocation(request.getGeographicalLocationId());

        location.setName(request.getName().trim());
        location.setType(locationType);
        location.setGeographicalLocationId(request.getGeographicalLocationId());

        if (request.getParents() != null) {
            location.setParents(new EnumMap<>(request.getParents()));
        } else {
            location.getParents().clear();
        }

        return toDto(locationRepository.save(location));
    }

    @Override
    public void deleteLocation(@NonNull UUID locationId) {
        Location location = getLocationEntity(locationId);
        locationRepository.delete(location);
    }

    private Location getLocationEntity(UUID locationId) {
        return locationRepository.findById(locationId)
                .orElseThrow(() -> new LocationNotFoundException(locationId));
    }

    private LocationType getLocationType(UUID typeId) {
        return locationTypeRepository.findById(typeId)
                .orElseThrow(() -> new LocationTypeNotFoundException(typeId));
    }

    private void validateGeographicalLocation(UUID geographicalLocationId) {
        if (geographicalLocationId == null) {
            return;
        }
        if (!geographicalLocationRepository.existsById(geographicalLocationId)) {
            throw new GeographicalLocationNotFoundException(geographicalLocationId);
        }
    }

    private LocationDto toDto(Location location) {
        return LocationDto.builder()
                .id(location.getId())
                .name(location.getName())
                .typeName(location.getType().getName())
                .parents(location.getParents())
                .geographicalLocationId(location.getGeographicalLocationId())
                .build();
    }
}
