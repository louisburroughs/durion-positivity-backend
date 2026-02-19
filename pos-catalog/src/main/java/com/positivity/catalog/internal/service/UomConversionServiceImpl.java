package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.UomConversionCreateRequestDto;
import com.positivity.catalog.internal.dto.UomConversionDto;
import com.positivity.catalog.internal.dto.UomConversionUpdateRequestDto;
import com.positivity.catalog.internal.entity.UomConversionEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.UomConversionRepository;
import com.positivity.catalog.service.UomConversionService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UomConversionServiceImpl implements UomConversionService {

    private final UomConversionRepository repository;

    @Override
    @Transactional
    public UomConversionDto createConversion(@NonNull UomConversionCreateRequestDto request) {
        String from = normalize(request.getFromUomCode());
        String to = normalize(request.getToUomCode());

        if (repository.findByFromUomCodeAndToUomCodeAndIsActiveTrue(from, to).isPresent()) {
            throw new CatalogBusinessRuleException("Conversion already exists for from/to pair");
        }
        validateFactor(from, to, request.getConversionFactor());

        UomConversionEntity entity = new UomConversionEntity();
        entity.setFromUomCode(from);
        entity.setToUomCode(to);
        entity.setConversionFactor(request.getConversionFactor().setScale(6, RoundingMode.HALF_UP));
        entity.setActive(true);
        entity.setCreatedBy(request.getCreatedBy());
        return toDto(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public UomConversionDto getConversion(@NonNull UUID id) {
        return toDto(findById(id));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UomConversionDto> listActiveConversions() {
        return repository.findAllByIsActiveTrue().stream().map(this::toDto).toList();
    }

    @Override
    @Transactional
    public UomConversionDto updateConversion(@NonNull UUID id, @NonNull UomConversionUpdateRequestDto request) {
        UomConversionEntity entity = findById(id);
        validateFactor(entity.getFromUomCode(), entity.getToUomCode(), request.getConversionFactor());
        entity.setConversionFactor(request.getConversionFactor().setScale(6, RoundingMode.HALF_UP));
        return toDto(repository.save(entity));
    }

    @Override
    @Transactional
    public void deactivateConversion(@NonNull UUID id) {
        UomConversionEntity entity = findById(id);
        entity.setActive(false);
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal getInverseConversionFactor(@NonNull String fromUomCode, @NonNull String toUomCode) {
        UomConversionEntity entity = repository
                .findByFromUomCodeAndToUomCodeAndIsActiveTrue(normalize(fromUomCode), normalize(toUomCode))
                .orElseThrow(() -> new CatalogNotFoundException(
                        "Conversion not found: " + fromUomCode + " -> " + toUomCode));
        return BigDecimal.ONE.divide(entity.getConversionFactor(), 6, RoundingMode.HALF_UP);
    }

    private void validateFactor(String from, String to, BigDecimal factor) {
        if (factor == null || factor.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("conversionFactor must be greater than zero");
        }
        if (from.equalsIgnoreCase(to) && factor.compareTo(BigDecimal.ONE) != 0) {
            throw new CatalogValidationException("Self conversion requires factor 1");
        }
    }

    private UomConversionEntity findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new CatalogNotFoundException("UOM conversion not found: " + id));
    }

    private String normalize(String code) {
        if (code == null || code.isBlank()) {
            throw new CatalogValidationException("UOM code is required");
        }
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private UomConversionDto toDto(UomConversionEntity entity) {
        return UomConversionDto.builder()
                .id(entity.getId())
                .fromUomCode(entity.getFromUomCode())
                .toUomCode(entity.getToUomCode())
                .conversionFactor(entity.getConversionFactor())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .createdBy(entity.getCreatedBy())
                .build();
    }
}
