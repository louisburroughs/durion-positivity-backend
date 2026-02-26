package com.positivity.location.internal.service;

import com.positivity.location.service.ServiceAreaService;

import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.exception.DuplicateResourceException;
import com.positivity.location.internal.exception.ResourceNotFoundException;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Public API for service area management.
 *
 * Issue: #76
 */
@Service
public class ServiceAreaServiceImpl implements ServiceAreaService {

    private static final String SERVICE_AREA_NAME_TAKEN = "SERVICE_AREA_NAME_TAKEN";
    private static final String SERVICE_AREA_CONFLICT = "SERVICE_AREA_CONFLICT";
    private static final String ACTIVE = "active";
    private static final String DESCRIPTION = "description";
    protected final ServiceAreaRepository serviceAreaRepository;

    public ServiceAreaServiceImpl(ServiceAreaRepository serviceAreaRepository) {
        this.serviceAreaRepository = serviceAreaRepository;
    }

    /**
     * Creates a service area from a map payload.
     *
     * @param request story #76 payload
     * @return created service area response
     */
    @Transactional
    public ServiceAreaResponse create(Map<String, Object> request) {
        return create(toRequest(request));
    }

    /**
     * Creates a service area from a typed request.
     *
     * @param request typed payload
     * @return created service area response
     */
    @Transactional
    public ServiceAreaResponse create(ServiceAreaRequest request) {
        validatePostalCodes(request.getPostalCodes());

        ServiceAreaEntity entity = ServiceAreaEntity.builder()
                .name(request.getName())
                .description(request.getDescription())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .postalCodes(toPostalValues(request.getPostalCodes()))
                .build();

        ServiceAreaEntity saved;
        try {
            saved = serviceAreaRepository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            throw toServiceAreaConflictException(exception);
        }
        return toResponse(saved);
    }

    /**
     * Patches a service area.
     *
     * @param id    service area identifier
     * @param patch map payload
     * @return updated service area response
     */
    @Transactional
    public ServiceAreaResponse patch(String id, Map<String, Object> patch) {
        UUID areaId = parseUuidStrict(id);
        ServiceAreaEntity entity = serviceAreaRepository.findById(areaId)
                .orElseThrow(() -> new ResourceNotFoundException("Service area not found"));

        if (patch.containsKey(DESCRIPTION)) {
            entity.setDescription((String) patch.get(DESCRIPTION));
        }
        if (patch.containsKey(ACTIVE)) {
            entity.setActive(Boolean.valueOf(String.valueOf(patch.get(ACTIVE))));
        }

        ServiceAreaEntity saved;
        try {
            saved = serviceAreaRepository.save(entity);
        } catch (DataIntegrityViolationException exception) {
            throw toServiceAreaConflictException(exception);
        }
        return toResponse(saved);
    }

    /**
     * Lists all service areas.
     *
     * @return service area responses
     */
    @Transactional(readOnly = true)
    public List<ServiceAreaResponse> list() {
        if (serviceAreaRepository == null) {
            return List.of();
        }
        return serviceAreaRepository.findAll().stream().map(this::toResponse).toList();
    }

    private ServiceAreaRequest toRequest(Map<String, Object> map) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawPostalCodes = (List<Map<String, Object>>) map.getOrDefault("postalCodes",
                List.of());
        List<ServiceAreaRequest.PostalCodeEntry> postalCodes = new ArrayList<>();
        for (Map<String, Object> rawPostalCode : rawPostalCodes) {
            postalCodes.add(ServiceAreaRequest.PostalCodeEntry.builder()
                    .postalCode((String) rawPostalCode.get("postalCode"))
                    .countryCode((String) rawPostalCode.get("countryCode"))
                    .build());
        }

        return ServiceAreaRequest.builder()
                .name((String) map.get("name"))
                .description((String) map.get(DESCRIPTION))
                .active(map.get(ACTIVE) == null ? null : Boolean.valueOf(String.valueOf(map.get(ACTIVE))))
                .postalCodes(postalCodes)
                .build();
    }

    private void validatePostalCodes(List<ServiceAreaRequest.PostalCodeEntry> postalCodes) {
        if (postalCodes == null || postalCodes.isEmpty()) {
            throw new IllegalArgumentException("service area must include at least one postal code");
        }
        boolean missingCountryCode = postalCodes.stream()
                .anyMatch(entry -> entry.getCountryCode() == null || entry.getCountryCode().isBlank());
        if (missingCountryCode) {
            throw new IllegalArgumentException("postal code entries require countryCode");
        }
    }

    private Set<ServiceAreaPostalCodeValue> toPostalValues(List<ServiceAreaRequest.PostalCodeEntry> postalCodes) {
        Set<ServiceAreaPostalCodeValue> values = new LinkedHashSet<>();
        if (postalCodes == null) {
            return values;
        }
        for (ServiceAreaRequest.PostalCodeEntry postalCode : postalCodes) {
            values.add(ServiceAreaPostalCodeValue.builder()
                    .postalCode(postalCode.getPostalCode())
                    .countryCode(postalCode.getCountryCode())
                    .build());
        }
        return values;
    }

    private ServiceAreaResponse toResponse(ServiceAreaEntity entity) {
        List<ServiceAreaRequest.PostalCodeEntry> postalCodes = entity.getPostalCodes() == null
                ? List.of()
                : entity.getPostalCodes().stream()
                        .map(value -> ServiceAreaRequest.PostalCodeEntry.builder()
                                .postalCode(value.getPostalCode())
                                .countryCode(value.getCountryCode())
                                .build())
                        .toList();

        return ServiceAreaResponse.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .active(entity.getActive())
                .postalCodes(postalCodes)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    private UUID parseUuidStrict(String id) {
        try {
            return UUID.fromString(id);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid id: " + id, exception);
        }
    }

    private DuplicateResourceException toServiceAreaConflictException(DataIntegrityViolationException exception) {
        if (isNameConstraintViolation(exception)) {
            return new DuplicateResourceException(SERVICE_AREA_NAME_TAKEN);
        }
        return new DuplicateResourceException(SERVICE_AREA_CONFLICT);
    }

    private boolean isNameConstraintViolation(Throwable throwable) {
        String details = lowerCaseMessages(throwable);
        return details.contains("service_areas_name_key")
                || details.contains("service_areas")
                || details.contains(" name ");
    }

    private String lowerCaseMessages(Throwable throwable) {
        StringBuilder all = new StringBuilder();
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = cursor.getMessage();
            if (message != null) {
                all.append(message.toLowerCase(Locale.ROOT)).append(' ');
            }
            cursor = cursor.getCause();
        }
        return all.toString();
    }
}
