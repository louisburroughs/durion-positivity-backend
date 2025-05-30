package com.positivity.location.controller;

import com.positivity.location.entity.Location;
import com.positivity.location.entity.LocationParent;
import com.positivity.location.entity.ParentType;
import com.positivity.location.dto.PersonDTO;
import com.positivity.location.service.LocationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/locations")
@RequiredArgsConstructor
public class LocationController {
    private final LocationService locationService;

    @GetMapping
    public List<Location> getAllLocations() {
        return locationService.getAllLocations();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Location> getLocationById(@PathVariable Long id) {
        return locationService.getLocationById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Location> createLocation(@RequestBody Location location) {
        Location saved = locationService.saveLocation(location);
        return ResponseEntity.ok(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Location> updateLocation(@PathVariable Long id, @RequestBody Location location) {
        if (!locationService.getLocationById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        location.setId(id);
        Location updated = locationService.saveLocation(location);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteLocation(@PathVariable Long id) {
        if (!locationService.getLocationById(id).isPresent()) {
            return ResponseEntity.notFound().build();
        }
        locationService.deleteLocation(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{childId}/parents/{parentId}")
    public ResponseEntity<LocationParent> addParent(
            @PathVariable Long childId,
            @PathVariable Long parentId,
            @RequestParam ParentType parentType) {
        LocationParent parent = locationService.addParent(childId, parentId, parentType);
        return ResponseEntity.ok(parent);
    }

    @GetMapping("/parents")
    public List<LocationParent> getAllParents() {
        return locationService.getAllParents();
    }

    @GetMapping("/{id}/responsible-person")
    public ResponseEntity<PersonDTO> getResponsiblePerson(@PathVariable Long id) {
        PersonDTO person = locationService.getResponsiblePerson(id);
        if (person == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(person);
    }
}
