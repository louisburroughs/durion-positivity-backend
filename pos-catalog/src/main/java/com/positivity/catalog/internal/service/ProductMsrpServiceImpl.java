package com.positivity.catalog.internal.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.positivity.catalog.internal.dto.CreateMsrpRequestDto;
import com.positivity.catalog.internal.dto.ProductMsrpDto;
import com.positivity.catalog.internal.dto.UpdateMsrpRequestDto;
import com.positivity.catalog.internal.entity.ProductMsrpEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.ProductMsrpRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.ProductMsrpService;

import jakarta.persistence.OptimisticLockException;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Service
public class ProductMsrpServiceImpl implements ProductMsrpService {

    private final Clock clock;
    private final ProductMsrpRepository productMsrpRepository;
    private final ProductRepository productRepository;

    @Override
    @Transactional
    public ProductMsrpDto createMsrp(@NonNull UUID productId, @NonNull CreateMsrpRequestDto request) {
        validateProductExists(productId);
        validateDates(request.getEffectiveStartDate(), request.getEffectiveEndDate());
        validateAmountAndCurrency(request.getAmount(), request.getCurrency());

        if (request.getEffectiveEndDate() == null
                && productMsrpRepository.countByProductIdAndEffectiveEndDateIsNull(productId) > 0) {
            throw new CatalogValidationException(
                    "Only one open-ended MSRP record is allowed per product.");
        }

        var overlaps = productMsrpRepository.findOverlapping(
                productId,
                request.getEffectiveStartDate(),
                request.getEffectiveEndDate(),
                null);
        if (!overlaps.isEmpty()) {
            throw new CatalogBusinessRuleException("MSRP dates overlap with existing record.");
        }

        ProductMsrpEntity entity = new ProductMsrpEntity();
        entity.setProductId(productId);
        entity.setAmount(request.getAmount().setScale(4, RoundingMode.HALF_UP));
        entity.setCurrency(normalizeCurrency(request.getCurrency()));
        entity.setEffectiveStartDate(request.getEffectiveStartDate());
        entity.setEffectiveEndDate(request.getEffectiveEndDate());
        entity.setUpdatedBy(request.getCreatedByUserId());

        return toDto(productMsrpRepository.save(entity));
    }

    @Override
    @Transactional
    public ProductMsrpDto updateMsrp(@NonNull UUID productId, @NonNull UUID msrpId,
            @NonNull UpdateMsrpRequestDto request) {
        // 1) Validate basic request integrity before touching persistence.
        validateProductExists(productId);
        validateDates(request.getEffectiveStartDate(), request.getEffectiveEndDate());
        validateAmountAndCurrency(request.getAmount(), request.getCurrency());

        // 2) Load target record and enforce ownership by product.
        ProductMsrpEntity entity = productMsrpRepository.findById(msrpId)
                .orElseThrow(() -> new CatalogNotFoundException("MSRP record not found: " + msrpId));

        if (!entity.getProductId().equals(productId)) {
            throw new CatalogNotFoundException("MSRP record not found for product: " + productId);
        }

        // 3) Protect historical data from retroactive edits.
        if (entity.getEffectiveEndDate() != null && entity.getEffectiveEndDate().isBefore(LocalDate.now(clock))) {
            throw new CatalogValidationException("Historical MSRP records are immutable.");
        }

        // 4) Enforce optimistic locking when a version is supplied.
        if (request.getVersion() != null && !request.getVersion().equals(entity.getVersion())) {
            throw new OptimisticLockException("Version mismatch for MSRP update.");
        }

        // 5) Enforce "single open-ended MSRP per product" invariant.
        // If this update sets effectiveEndDate to null, allow it only when no other
        // open-ended record exists (excluding this record if it is already open-ended).
        if (request.getEffectiveEndDate() == null) {
            long openEndedCount = productMsrpRepository.countByProductIdAndEffectiveEndDateIsNull(productId);
            boolean entityCurrentlyOpenEnded = entity.getEffectiveEndDate() == null;
            long otherOpenEndedCount = entityCurrentlyOpenEnded ? openEndedCount - 1 : openEndedCount;
            if (otherOpenEndedCount > 0) {
                throw new CatalogValidationException(
                        "Only one open-ended MSRP record is allowed per product.");
            }
        }

        // 6) Reject updates that create an overlapping effective date window.
        var overlaps = productMsrpRepository.findOverlapping(
                productId,
                request.getEffectiveStartDate(),
                request.getEffectiveEndDate(),
                msrpId);
        if (!overlaps.isEmpty()) {
            throw new CatalogBusinessRuleException("MSRP dates overlap with existing record.");
        }

        // 7) Apply normalized values and persist.
        entity.setAmount(request.getAmount().setScale(4, RoundingMode.HALF_UP));
        entity.setCurrency(normalizeCurrency(request.getCurrency()));
        entity.setEffectiveStartDate(request.getEffectiveStartDate());
        entity.setEffectiveEndDate(request.getEffectiveEndDate());
        entity.setUpdatedBy(request.getCreatedByUserId());

        return toDto(productMsrpRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProductMsrpDto> getActiveMsrp(@NonNull UUID productId, @NonNull LocalDate asOf) {
        validateProductExists(productId);
        return productMsrpRepository.findActive(productId, asOf).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProductMsrpDto> getAllMsrp(@NonNull UUID productId) {
        validateProductExists(productId);
        return productMsrpRepository.findByProductIdOrderByEffectiveStartDateDesc(productId)
                .stream()
                .map(this::toDto)
                .toList();
    }

    private void validateProductExists(UUID productId) {
        if (!productRepository.existsById(productId)) {
            throw new CatalogNotFoundException("Product not found: " + productId);
        }
    }

    private void validateDates(LocalDate startDate, LocalDate endDate) {
        if (startDate == null) {
            throw new CatalogValidationException("effectiveStartDate is required.");
        }
        if (endDate != null && endDate.isBefore(startDate)) {
            throw new CatalogValidationException("effectiveEndDate must be on or after effectiveStartDate.");
        }
    }

    private void validateAmountAndCurrency(BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new CatalogValidationException("amount must be positive.");
        }
        if (currency == null || currency.isBlank() || currency.trim().length() != 3) {
            throw new CatalogValidationException("currency must be a valid 3-letter ISO code.");
        }
    }

    private String normalizeCurrency(String currency) {
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private ProductMsrpDto toDto(ProductMsrpEntity entity) {
        ProductMsrpDto dto = new ProductMsrpDto();
        dto.setMsrpId(entity.getMsrpId());
        dto.setProductId(entity.getProductId());
        dto.setAmount(entity.getAmount().toPlainString());
        dto.setCurrency(entity.getCurrency());
        dto.setEffectiveStartDate(entity.getEffectiveStartDate());
        dto.setEffectiveEndDate(entity.getEffectiveEndDate());
        dto.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
        dto.setUpdatedBy(entity.getUpdatedBy());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private OffsetDateTime toOffsetDateTime(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
