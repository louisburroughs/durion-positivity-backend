package com.positivity.location.service;

import com.positivity.location.internal.client.PersonClient;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.repository.LocationParentRepository;
import com.positivity.location.internal.repository.LocationRepository;
import com.positivity.location.internal.dto.PersonDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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

    public Optional<Location> getLocationById(UUID id) {
        return locationRepository.findById(id);
    }

    @Transactional
    public Location saveLocation(Location location) {
        return locationRepository.save(location);
    }

    public void deleteLocation(UUID id) {
        locationRepository.deleteById(id);
    }

    @Transactional
    public LocationParent addParent(UUID childId, UUID parentId, ParentType parentType) {
        if (childId.equals(parentId)) {
            throw new IllegalArgumentException("A location cannot be its own parent");
        }
        if (locationParentRepository.existsByChild_IdAndParentType(childId, parentType)) {
            throw new IllegalStateException("Location already has a parent for parentType " + parentType);
        }
        Location child = locationRepository.findById(childId).orElseThrow();
        Location parent = locationRepository.findById(parentId).orElseThrow();
        LocationParent locationParent = LocationParent.builder()
                .child(child)
                .parent(parent)
                .parentType(parentType)
                .build();
        return locationParentRepository.save(locationParent);
    }

    @Transactional(readOnly = true)
    public List<LocationParent> getAllParents() {
        return locationParentRepository.findAll();
    }

    @Transactional(readOnly = true)
    public List<Location> getAllChildren(UUID parentId) {
        return locationParentRepository.findByParent_Id(parentId).stream()
                .map(LocationParent::getChild)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<Location> getAllChildren(UUID parentId, ParentType parentType) {
        if (parentType == null) {
            return locationParentRepository.findByParent_Id(parentId).stream()
                    .map(LocationParent::getChild)
                    .toList();
        }
        return locationParentRepository.findByParent_IdAndParentType(parentId, parentType).stream()
                .map(LocationParent::getChild)
                .toList();
    }

    public PersonDTO getResponsiblePerson(UUID locationId) {
        Location location = locationRepository.findById(locationId).orElseThrow();
        if (location.getResponsiblePersonId() == null)
            return null;
        return personClient.getPersonById(location.getResponsiblePersonId());
    }
}
