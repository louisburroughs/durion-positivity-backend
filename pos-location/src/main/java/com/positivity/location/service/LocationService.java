package com.positivity.location.service;

import com.positivity.location.client.PersonClient;
import com.positivity.location.entity.Location;
import com.positivity.location.entity.LocationParent;
import com.positivity.location.entity.ParentType;
import com.positivity.location.repository.LocationParentRepository;
import com.positivity.location.repository.LocationRepository;
import com.positivity.location.dto.PersonDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class LocationService {
    private final LocationRepository locationRepository;
    private final LocationParentRepository locationParentRepository;
    private final PersonClient personClient;

    public List<Location> getAllLocations() {
        return locationRepository.findAll();
    }

    public Optional<Location> getLocationById(Long id) {
        return locationRepository.findById(id);
    }

    @Transactional
    public Location saveLocation(Location location) {
        return locationRepository.save(location);
    }

    public void deleteLocation(Long id) {
        locationRepository.deleteById(id);
    }

    @Transactional
    public LocationParent addParent(Long childId, Long parentId, ParentType parentType) {
        Location child = locationRepository.findById(childId).orElseThrow();
        Location parent = locationRepository.findById(parentId).orElseThrow();
        LocationParent locationParent = LocationParent.builder()
                .child(child)
                .parent(parent)
                .parentType(parentType)
                .build();
        return locationParentRepository.save(locationParent);
    }

    public List<LocationParent> getAllParents() {
        return locationParentRepository.findAll();
    }

    public PersonDTO getResponsiblePerson(Long locationId) {
        Location location = locationRepository.findById(locationId).orElseThrow();
        if (location.getResponsiblePersonId() == null) return null;
        return personClient.getPersonById(location.getResponsiblePersonId());
    }
}
