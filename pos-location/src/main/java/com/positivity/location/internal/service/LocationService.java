package com.positivity.location.internal.service;

import com.positivity.location.internal.dto.LocationDescendantResponseDTO;
import com.positivity.location.internal.dto.LocationParentResponseDTO;
import com.positivity.location.internal.dto.LocationPatchRequest;
import com.positivity.location.internal.dto.LocationRequestDTO;
import com.positivity.location.internal.dto.LocationResponseDTO;
import com.positivity.location.internal.dto.LocationValidationResponseDTO;
import com.positivity.location.internal.dto.PersonDTO;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface LocationService {

    List<LocationResponseDTO> getAllLocationsDto();

    Optional<LocationResponseDTO> getLocationByIdDto(UUID id);

    Page<LocationResponseDTO> listLocations(String status, Pageable pageable);

    LocationValidationResponseDTO getLocationValidation(UUID id);

    LocationResponseDTO createLocation(LocationRequestDTO request);

    Optional<LocationResponseDTO> updateLocation(UUID id, LocationRequestDTO request);

    LocationResponseDTO patchLocation(UUID id, LocationPatchRequest patch);

    LocationParentResponseDTO addParent(UUID childId, UUID parentId, String parentTypeValue);

    List<LocationParentResponseDTO> getAllParentsDto();

    List<LocationResponseDTO> getAllChildrenDto(UUID parentId, String parentTypeValue);

    /**
     * Walks parent-child edges of the given type downward from a location and
     * returns every descendant as a flat list with depth and immediate parent.
     * Traversal is cycle-safe and depth-capped.
     *
     * Issue: CAP-214 #655
     *
     * @param locationId      root location id
     * @param parentTypeValue parent relationship type to traverse; defaults to
     *                        PHYSICAL when null or blank
     * @return flat list of descendants (empty when the location has none)
     */
    List<LocationDescendantResponseDTO> getDescendantsDto(UUID locationId, String parentTypeValue);

    void deleteLocation(UUID id);

    PersonDTO getResponsiblePerson(UUID locationId);
}
