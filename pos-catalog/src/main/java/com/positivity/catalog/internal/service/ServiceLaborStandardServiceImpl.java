package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ServiceLaborStandardRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardResponseDto;
import com.positivity.catalog.internal.entity.ServiceLaborStandardEntity;
import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ServiceLaborStandardRepository;
import com.positivity.catalog.internal.repository.ServiceRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ServiceLaborStandardServiceImpl implements ServiceLaborStandardService {

    /** Provenance source code for rows authored through this API rather than imported. */
    static final String DURION_SOURCE = "DURION";

    private final ServiceRepository serviceRepository;
    private final ServiceLaborStandardRepository laborStandardRepository;
    private final Clock clock;

    public ServiceLaborStandardServiceImpl(
            ServiceRepository serviceRepository, ServiceLaborStandardRepository laborStandardRepository, Clock clock) {
        this.serviceRepository = serviceRepository;
        this.laborStandardRepository = laborStandardRepository;
        this.clock = clock;
    }

    @Override
    @NonNull
    @Transactional
    public ServiceLaborStandardResponseDto create(
            @NonNull UUID serviceId, @NonNull ServiceLaborStandardRequestDto request) {
        requireServiceExists(serviceId);
        ServiceLaborStandardEntity entity = validatedEntity(serviceId, request);
        rejectDuplicateActiveRow(serviceId, entity, null);
        return toResponse(laborStandardRepository.save(entity));
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<ServiceLaborStandardResponseDto> list(@NonNull UUID serviceId, boolean includeSuperseded) {
        requireServiceExists(serviceId);
        List<ServiceLaborStandardEntity> rows = includeSuperseded
                ? laborStandardRepository.findByServiceIdOrderByCreatedAtAsc(serviceId)
                : laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(serviceId);
        return rows.stream().map(this::toResponse).toList();
    }

    @Override
    @NonNull
    @Transactional
    public ServiceLaborStandardResponseDto supersede(
            @NonNull UUID serviceId, @NonNull UUID standardId, @NonNull ServiceLaborStandardRequestDto request) {
        requireServiceExists(serviceId);
        ServiceLaborStandardEntity existing = laborStandardRepository
                .findById(standardId)
                .filter(row -> serviceId.equals(row.getServiceId()))
                .orElseThrow(() -> new CatalogNotFoundException(
                        "Labor standard " + standardId + " not found for service " + serviceId));
        if (existing.getSupersededAt() != null) {
            throw new CatalogBusinessRuleException(
                    "Labor standard " + standardId + " is already superseded; supersede its active replacement");
        }
        if (!DURION_SOURCE.equals(existing.getSourceCode())) {
            throw new CatalogBusinessRuleException("Labor standard " + standardId + " came from source "
                    + existing.getSourceCode()
                    + "; imported rows are corrected by their source's next import, not by hand");
        }
        ServiceLaborStandardEntity replacement = validatedEntity(serviceId, request);
        rejectDuplicateActiveRow(serviceId, replacement, standardId);
        existing.setSupersededAt(Instant.now(clock));
        // Flushed before the replacement is inserted: Hibernate orders inserts ahead of updates
        // within a flush, and the V18 active-key unique index would otherwise see the old row
        // still active when the replacement lands on the same vehicle key.
        laborStandardRepository.saveAndFlush(existing);
        return toResponse(laborStandardRepository.save(replacement));
    }

    private void requireServiceExists(UUID serviceId) {
        if (!serviceRepository.existsById(serviceId)) {
            throw new CatalogNotFoundException("Service not found: " + serviceId);
        }
    }

    private ServiceLaborStandardEntity validatedEntity(UUID serviceId, ServiceLaborStandardRequestDto request) {
        ServiceLaborStandardEntity entity = new ServiceLaborStandardEntity();
        entity.setServiceId(serviceId);
        entity.setVehicleYear(trimToNull(request.getVehicleYear()));
        entity.setMake(trimToNull(request.getMake()));
        entity.setModel(trimToNull(request.getModel()));
        entity.setSubmodel(trimToNull(request.getSubmodel()));
        entity.setEngineCode(trimToNull(request.getEngineCode()));
        entity.setLaborHours(LaborTimeValidation.validatedTenthsHours(requireHours(request), "laborHours"));
        entity.setTimeType(parsedTimeType(request.getTimeType()));
        entity.setOverlapGroup(trimToNull(request.getOverlapGroup()));
        entity.setIncludedOpCodes(validatedIncludedOpCodes(request.getIncludedOpCodes()));
        entity.setSourceCode(DURION_SOURCE);
        // For a hand-authored row the vintage is the moment of authoring; imported rows will carry
        // their feed's revision instead.
        entity.setSourceRevision(Instant.now(clock).toString());
        entity.setPublishedAt(request.getPublishedAt());
        return entity;
    }

    private static java.math.BigDecimal requireHours(ServiceLaborStandardRequestDto request) {
        if (request.getLaborHours() == null) {
            throw new CatalogValidationException("laborHours is required");
        }
        return request.getLaborHours();
    }

    private LaborTimeType parsedTimeType(String timeType) {
        if (timeType == null || timeType.isBlank()) {
            return LaborTimeType.DURION_STANDARD;
        }
        try {
            return LaborTimeType.valueOf(timeType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new CatalogValidationException(
                    "timeType must be one of " + java.util.Arrays.toString(LaborTimeType.values()) + ": " + timeType);
        }
    }

    private List<String> validatedIncludedOpCodes(List<String> codes) {
        if (codes == null || codes.isEmpty()) {
            return null;
        }
        return codes.stream()
                .map(code -> LaborTimeValidation.validatedOperationCodeShape(code, "includedOpCodes entry"))
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Two active rows answering the same (vehicle key, time type) would make resolution
     * ambiguous; the correction path for a wrong number is supersession, not a second row.
     */
    private void rejectDuplicateActiveRow(
            UUID serviceId, ServiceLaborStandardEntity candidate, UUID beingSupersededId) {
        laborStandardRepository.findByServiceIdAndSupersededAtIsNullOrderByCreatedAtAsc(serviceId).stream()
                .filter(row -> !row.getId().equals(beingSupersededId))
                .filter(row -> row.getTimeType() == candidate.getTimeType()
                        && Objects.equals(row.getVehicleYear(), candidate.getVehicleYear())
                        && Objects.equals(row.getMake(), candidate.getMake())
                        && Objects.equals(row.getModel(), candidate.getModel())
                        && Objects.equals(row.getSubmodel(), candidate.getSubmodel())
                        && Objects.equals(row.getEngineCode(), candidate.getEngineCode()))
                .findFirst()
                .ifPresent(row -> {
                    throw new CatalogBusinessRuleException("An active " + row.getTimeType()
                            + " labor standard already exists for this vehicle key (" + row.getId()
                            + "); supersede it instead of adding a duplicate");
                });
    }

    private ServiceLaborStandardResponseDto toResponse(ServiceLaborStandardEntity entity) {
        ServiceLaborStandardResponseDto dto = new ServiceLaborStandardResponseDto();
        dto.setId(entity.getId());
        dto.setServiceId(entity.getServiceId());
        dto.setVehicleYear(entity.getVehicleYear());
        dto.setMake(entity.getMake());
        dto.setModel(entity.getModel());
        dto.setSubmodel(entity.getSubmodel());
        dto.setEngineCode(entity.getEngineCode());
        dto.setLaborHours(entity.getLaborHours());
        dto.setTimeType(entity.getTimeType().name());
        dto.setOverlapGroup(entity.getOverlapGroup());
        dto.setIncludedOpCodes(entity.getIncludedOpCodes());
        dto.setSourceCode(entity.getSourceCode());
        dto.setSourceRevision(entity.getSourceRevision());
        dto.setPublishedAt(entity.getPublishedAt());
        dto.setSupersededAt(entity.getSupersededAt());
        dto.setCreatedAt(entity.getCreatedAt());
        return dto;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
