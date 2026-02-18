package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.UpdateLocationRequest;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationType;
import com.positivity.location.internal.exception.LocationNotFoundException;
import com.positivity.location.internal.repository.GeographicalLocationRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.repository.LocationTypeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LocationServiceImplTest {

    @Mock
    private LocationRepository locationRepository;

    @Mock
    private LocationTypeRepository locationTypeRepository;

    @Mock
    private GeographicalLocationRepository geographicalLocationRepository;

    private LocationServiceImpl locationService;

    @BeforeEach
    void setUp() {
        locationService = new LocationServiceImpl(locationRepository, locationTypeRepository,
                geographicalLocationRepository);
    }

    @Test
    void listLocations_returnsMappedDtos() {
        LocationType type = type("Store");
        Location location = location("Main", type);
        when(locationRepository.findAll()).thenReturn(List.of(location));

        var results = locationService.listLocations();

        assertThat(results).hasSize(1);
        assertThat(results.getFirst().getName()).isEqualTo("Main");
    }

    @Test
    void getLocation_throwsNotFoundWhenMissing() {
        UUID missingId = UUID.randomUUID();
        when(locationRepository.findById(missingId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> locationService.getLocationById(missingId))
                .isInstanceOf(LocationNotFoundException.class);
    }

    @Test
    void updateLocation_updatesAndReturnsDto() {
        UUID locationId = UUID.randomUUID();
        UUID typeId = UUID.randomUUID();

        LocationType type = new LocationType();
        type.setId(typeId);
        type.setName("Store");

        Location existing = location("Before", type);
        existing.setId(locationId);

        UpdateLocationRequest request = new UpdateLocationRequest();
        request.setName("After");
        request.setTypeId(typeId);

        when(locationRepository.findById(locationId)).thenReturn(Optional.of(existing));
        when(locationTypeRepository.findById(typeId)).thenReturn(Optional.of(type));
        when(locationRepository.save(any(Location.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var updated = locationService.updateLocation(locationId, request);

        assertThat(updated.getName()).isEqualTo("After");
        assertThat(updated.getTypeName()).isEqualTo("Store");
    }

    @Test
    void deleteLocation_deletesExisting() {
        UUID locationId = UUID.randomUUID();
        LocationType type = type("Store");
        Location existing = location("To Delete", type);
        existing.setId(locationId);

        when(locationRepository.findById(locationId)).thenReturn(Optional.of(existing));

        locationService.deleteLocation(locationId);

        verify(locationRepository).delete(existing);
    }

    private LocationType type(String name) {
        LocationType type = new LocationType();
        type.setId(UUID.randomUUID());
        type.setName(name);
        return type;
    }

    private Location location(String name, LocationType type) {
        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setName(name);
        location.setType(type);
        return location;
    }
}
