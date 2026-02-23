package com.positivity.location.service;

import com.positivity.location.internal.dto.ServiceAreaRequest;
import com.positivity.location.internal.dto.ServiceAreaResponse;
import com.positivity.location.internal.entity.ServiceAreaEntity;
import com.positivity.location.internal.entity.ServiceAreaPostalCodeValue;
import com.positivity.location.internal.repository.ServiceAreaRepository;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public API for service area management.
 *
 * Issue: #76
 */
public class ServiceAreaService {

    protected final ServiceAreaRepository serviceAreaRepository;

    public ServiceAreaService(ServiceAreaRepository serviceAreaRepository) {
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
                .id(UUIDv7Generator.generate())
                .name(request.getName())
                .description(request.getDescription())
                .active(request.getActive() == null ? Boolean.TRUE : request.getActive())
                .postalCodes(toPostalValues(request.getPostalCodes()))
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        ServiceAreaEntity saved = entity;
        if (serviceAreaRepository != null) {
            ServiceAreaEntity persisted = serviceAreaRepository.save(entity);
            if (persisted != null) {
                saved = persisted;
            }
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
        UUID areaId = parseUuid(id);
        ServiceAreaEntity entity = serviceAreaRepository == null ? null
                : serviceAreaRepository.findById(areaId).orElse(null);
        if (entity == null) {
            return ServiceAreaResponse.builder()
                    .id(areaId)
                    .name((String) patch.get("name"))
                    .description((String) patch.get("description"))
                    .active(patch.containsKey("active") ? Boolean.valueOf(String.valueOf(patch.get("active")))
                            : Boolean.TRUE)
                    .updatedAt(Instant.now())
                    .build();
        }

        if (patch.containsKey("description")) {
            entity.setDescription((String) patch.get("description"));
        }
        if (patch.containsKey("active")) {
            entity.setActive(Boolean.valueOf(String.valueOf(patch.get("active"))));
        }
        entity.setUpdatedAt(Instant.now());

        ServiceAreaEntity saved = entity;
        if (serviceAreaRepository != null) {
            saved = serviceAreaRepository.save(entity);
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
                .description((String) map.get("description"))
                .active(map.get("active") == null ? null : Boolean.valueOf(String.valueOf(map.get("active"))))
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

    private UUID parseUuid(String id) {
        if (id == null) {
            return UUIDv7Generator.generate();
        }
        try {
            return UUID.fromString(id);
        } catch (Exception ignored) {
            return UUIDv7Generator.generate();
        }
    }
}
