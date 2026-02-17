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

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
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

    private boolean isDescendant(UUID ancestorId, UUID targetDescendantId) {
        Set<UUID> visited = new HashSet<>();
        Queue<UUID> queue = new ArrayDeque<>();
        queue.add(ancestorId);

        while (!queue.isEmpty()) {
            UUID current = queue.poll();
            if (!visited.add(current)) {
                continue;
            }
            List<LocationParent> children = locationParentRepository.findByParent_Id(current);
            for (LocationParent relation : children) {
                UUID childId = relation.getChild().getId();
                if (targetDescendantId.equals(childId)) {
                    return true;
                }
                queue.add(childId);
            }
        }
        return false;
    }
}
