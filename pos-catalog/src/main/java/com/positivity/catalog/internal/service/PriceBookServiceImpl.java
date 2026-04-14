package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.PriceBookCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookDto;
import com.positivity.catalog.internal.dto.PriceBookRuleCreateRequestDto;
import com.positivity.catalog.internal.dto.PriceBookRuleDto;
import com.positivity.catalog.internal.dto.ResolvePriceRequestDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto;
import com.positivity.catalog.internal.dto.ResolvePriceResponseDto.ResolvePriceSource;
import com.positivity.catalog.internal.entity.PriceBookEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleConditionType;
import com.positivity.catalog.internal.entity.PriceBookRuleEntity;
import com.positivity.catalog.internal.entity.PriceBookRuleStatus;
import com.positivity.catalog.internal.entity.PriceBookRuleTargetType;
import com.positivity.catalog.internal.entity.PriceBookScope;
import com.positivity.catalog.internal.entity.PriceBookStatus;
import com.positivity.catalog.internal.entity.ProductEntity;
import com.positivity.catalog.internal.exception.CatalogBusinessRuleException;
import com.positivity.catalog.internal.exception.CatalogNotFoundException;
import com.positivity.catalog.internal.exception.CatalogValidationException;
import com.positivity.catalog.internal.repository.PriceBookRepository;
import com.positivity.catalog.internal.repository.PriceBookRuleRepository;
import com.positivity.catalog.internal.repository.ProductMsrpRepository;
import com.positivity.catalog.internal.repository.ProductRepository;
import com.positivity.catalog.service.PriceBookService;
import jakarta.persistence.OptimisticLockException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RequiredArgsConstructor
@Service
public class PriceBookServiceImpl implements PriceBookService {

    private static final String PRICE_BASE_DATA_MISSING = "PRICE_BASE_DATA_MISSING";
    private static final Instant FAR_FUTURE_EFFECTIVE_END_AT = Instant.parse("2099-12-31T23:59:59Z");

    private final Clock clock;
    private final PriceBookRepository priceBookRepository;
    private final PriceBookRuleRepository priceBookRuleRepository;
    private final ProductRepository productRepository;
    private final ProductMsrpRepository productMsrpRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PriceBookDto createPriceBook(@NonNull PriceBookCreateRequestDto request) {
        validatePriceBookRequest(request);

        PriceBookEntity entity = new PriceBookEntity();
        entity.setName(request.getName().trim());
        entity.setScope(request.getScope());
        entity.setScopeId(request.getScopeId());
        entity.setDefault(Boolean.TRUE.equals(request.getIsDefault()));
        entity.setStatus(request.getStatus() == null ? PriceBookStatus.ACTIVE : request.getStatus());

        return toPriceBookDto(priceBookRepository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public PriceBookDto getPriceBook(@NonNull UUID priceBookId) {
        return toPriceBookDto(requirePriceBook(priceBookId));
    }

    @Override
    @Transactional
    public PriceBookDto updatePriceBook(@NonNull UUID priceBookId, @NonNull PriceBookCreateRequestDto request) {
        validatePriceBookRequest(request);

        PriceBookEntity entity = requirePriceBook(priceBookId);
        entity.setName(request.getName().trim());
        entity.setScope(request.getScope());
        entity.setScopeId(request.getScopeId());
        if (request.getIsDefault() != null) {
            entity.setDefault(request.getIsDefault());
        }
        if (request.getStatus() != null) {
            entity.setStatus(request.getStatus());
        }

        return toPriceBookDto(priceBookRepository.save(entity));
    }

    @Override
    @Transactional
    public PriceBookRuleDto createRule(@NonNull UUID priceBookId, @NonNull PriceBookRuleCreateRequestDto request) {
        PriceBookEntity priceBook = requirePriceBook(priceBookId);
        validateRuleRequest(request);
        ensureRuleNoConflict(priceBookId, request, null);

        PriceBookRuleEntity entity = new PriceBookRuleEntity();
        entity.setPriceBook(priceBook);
        entity.setTargetType(request.getTargetType());
        entity.setTargetId(request.getTargetId());
        entity.setPricingLogic(request.getPricingLogic());
        entity.setConditionType(
                request.getConditionType() == null ? PriceBookRuleConditionType.NONE : request.getConditionType());
        entity.setConditionValue(request.getConditionValue());
        entity.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        entity.setEffectiveStartAt(toInstant(request.getEffectiveStartAt()));
        entity.setEffectiveEndAt(toInstant(request.getEffectiveEndAt()));
        entity.setStatus(PriceBookRuleStatus.ACTIVE);
        entity.setCreatedByUserId(request.getCreatedByUserId());

        return toPriceBookRuleDto(priceBookRuleRepository.save(entity));
    }

    @Override
    @Transactional
    public PriceBookRuleDto updateRule(
            @NonNull UUID priceBookId, @NonNull UUID ruleId, @NonNull PriceBookRuleCreateRequestDto request) {
        validateRuleRequest(request);

        PriceBookRuleEntity entity = requireRule(priceBookId, ruleId);

        if (request.getVersion() != null && !request.getVersion().equals(entity.getVersion())) {
            throw new OptimisticLockException("Version mismatch for price book rule update.");
        }

        ensureRuleNoConflict(priceBookId, request, ruleId);

        entity.setTargetType(request.getTargetType());
        entity.setTargetId(request.getTargetId());
        entity.setPricingLogic(request.getPricingLogic());
        entity.setConditionType(
                request.getConditionType() == null ? PriceBookRuleConditionType.NONE : request.getConditionType());
        entity.setConditionValue(request.getConditionValue());
        entity.setPriority(request.getPriority() == null ? 0 : request.getPriority());
        entity.setEffectiveStartAt(toInstant(request.getEffectiveStartAt()));
        entity.setEffectiveEndAt(toInstant(request.getEffectiveEndAt()));
        entity.setCreatedByUserId(request.getCreatedByUserId());

        return toPriceBookRuleDto(priceBookRuleRepository.save(entity));
    }

    @Override
    @Transactional
    public void deactivateRule(@NonNull UUID priceBookId, @NonNull UUID ruleId) {
        PriceBookRuleEntity entity = requireRule(priceBookId, ruleId);
        entity.setStatus(PriceBookRuleStatus.INACTIVE);
        priceBookRuleRepository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceBookRuleDto> listRules(@NonNull UUID priceBookId) {
        requirePriceBook(priceBookId);
        return priceBookRuleRepository.findAllByPriceBookId(priceBookId).stream()
                .map(this::toPriceBookRuleDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvePriceResponseDto resolvePrice(@NonNull ResolvePriceRequestDto request) {
        if (request.getProductId() == null) {
            throw new CatalogValidationException("productId is required.");
        }

        ProductEntity product = productRepository
                .findById(request.getProductId())
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + request.getProductId()));

        LocalDate asOfDate = request.getAsOf() == null ? LocalDate.now(clock) : request.getAsOf();
        Instant asOf = asOfDate.atStartOfDay().toInstant(ZoneOffset.UTC);

        List<UUID> priceBookIds = resolveCandidatePriceBookIds(request);
        if (!priceBookIds.isEmpty()) {
            var candidates =
                    priceBookRuleRepository
                            .findActiveRulesForBooks(priceBookIds, PriceBookRuleStatus.ACTIVE, asOf)
                            .stream()
                            .filter(rule -> isRuleApplicable(rule, request, product))
                            .toList();

            var winner = pickWinner(candidates, product);
            if (winner != null) {
                return fromRule(winner, request);
            }
        }

        return productMsrpRepository
                .findActive(request.getProductId(), asOfDate)
                .map(msrp -> {
                    ResolvePriceResponseDto dto = new ResolvePriceResponseDto();
                    dto.setResolvedAmount(
                            msrp.getAmount().setScale(4, RoundingMode.HALF_UP).toPlainString());
                    dto.setCurrency(msrp.getCurrency());
                    dto.setSource(ResolvePriceSource.MSRP);
                    dto.setFallbackReason("MSRP_FALLBACK");
                    return dto;
                })
                .orElseGet(() -> {
                    ResolvePriceResponseDto dto = new ResolvePriceResponseDto();
                    dto.setSource(ResolvePriceSource.UNAVAILABLE);
                    dto.setFallbackReason(PRICE_BASE_DATA_MISSING);
                    return dto;
                });
    }

    private ResolvePriceResponseDto fromRule(PriceBookRuleEntity rule, ResolvePriceRequestDto request) {
        PricePayload payload = parsePricePayload(rule.getPricingLogic(), request.getCurrency());
        ResolvePriceResponseDto dto = new ResolvePriceResponseDto();
        dto.setResolvedAmount(payload.amount().setScale(4, RoundingMode.HALF_UP).toPlainString());
        dto.setCurrency(payload.currency());
        dto.setSource(ResolvePriceSource.PRICE_BOOK_RULE);
        dto.setSourceRuleId(rule.getRuleId());
        return dto;
    }

    private PricePayload parsePricePayload(String pricingLogic, String requestedCurrency) {
        try {
            JsonNode root = objectMapper.readTree(pricingLogic);
            JsonNode amountsNode = root.path("amounts");
            if (!amountsNode.isObject() || amountsNode.isEmpty()) {
                throw new CatalogValidationException("pricingLogic.amounts must be a non-empty object.");
            }

            String selectedCurrency = selectConfiguredCurrency(
                    amountsNode, requestedCurrency, root.path("defaultCurrency").asString(null));

            JsonNode amountNode = amountsNode.get(selectedCurrency);
            if (amountNode == null || amountNode.isNull()) {
                throw new CatalogValidationException("No amount configured for currency: " + selectedCurrency);
            }

            BigDecimal amount = new BigDecimal(amountNode.asString());
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new CatalogValidationException("Resolved amount must be positive.");
            }

            return new PricePayload(amount, selectedCurrency);
        } catch (CatalogValidationException e) {
            throw e;
        } catch (Exception e) {
            throw new CatalogValidationException(
                    "pricingLogic must be valid JSON using {\"amounts\": {\"USD\": \"10.00\"}, \"defaultCurrency\": \"USD\"}.");
        }
    }

    private String selectConfiguredCurrency(JsonNode amountsNode, String requestedCurrency, String defaultCurrency) {
        if (requestedCurrency != null && !requestedCurrency.isBlank()) {
            String normalizedRequested = normalizeCurrency(requestedCurrency, "request.currency");
            if (!amountsNode.has(normalizedRequested)) {
                throw new CatalogValidationException(
                        "Requested currency is not configured in pricingLogic.amounts: " + normalizedRequested);
            }
            return normalizedRequested;
        }

        if (defaultCurrency != null && !defaultCurrency.isBlank()) {
            String normalizedDefault = normalizeCurrency(defaultCurrency, "pricingLogic.defaultCurrency");
            if (!amountsNode.has(normalizedDefault)) {
                throw new CatalogValidationException(
                        "pricingLogic.defaultCurrency is not present in pricingLogic.amounts: " + normalizedDefault);
            }
            return normalizedDefault;
        }

        if (amountsNode.size() == 1) {
            return resolveSingleConfiguredCurrency(amountsNode);
        }

        throw new CatalogValidationException(
                "request.currency is required when pricingLogic.amounts has multiple currencies and no defaultCurrency.");
    }

    private String resolveSingleConfiguredCurrency(JsonNode amountsNode) {
        var properties = amountsNode.properties();
        if (properties.isEmpty()) {
            throw new CatalogValidationException("pricingLogic.amounts must be a non-empty object.");
        }
        return normalizeCurrency(properties.iterator().next().getKey(), "pricingLogic.amounts key");
    }

    private String normalizeCurrency(String currency, String fieldName) {
        String normalized = currency == null ? null : currency.trim().toUpperCase(Locale.ROOT);
        if (normalized == null || normalized.length() != 3) {
            throw new CatalogValidationException(fieldName + " must be a 3-letter ISO currency code.");
        }
        return normalized;
    }

    private PriceBookRuleEntity pickWinner(List<PriceBookRuleEntity> candidates, ProductEntity product) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<PriceBookRuleEntity> mutable = new ArrayList<>(candidates);
        mutable.sort(Comparator.comparingInt((PriceBookRuleEntity r) -> precedenceScore(r, product))
                .reversed()
                .thenComparing(PriceBookRuleEntity::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(
                        PriceBookRuleEntity::getEffectiveStartAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.getRuleId().toString()));
        return mutable.get(0);
    }

    private int precedenceScore(PriceBookRuleEntity rule, ProductEntity product) {
        if (rule.getTargetType() == PriceBookRuleTargetType.SKU && skuTargetMatches(rule.getTargetId(), product)) {
            return 3;
        }
        if (rule.getTargetType() == PriceBookRuleTargetType.CATEGORY
                && taxonomyTargetMatches(rule.getTargetId(), product)) {
            return 2;
        }
        if (rule.getTargetType() == PriceBookRuleTargetType.GLOBAL) {
            return 1;
        }
        return 0;
    }

    private boolean isRuleApplicable(PriceBookRuleEntity rule, ResolvePriceRequestDto request, ProductEntity product) {
        boolean targetMatch =
                switch (rule.getTargetType()) {
                    case SKU -> skuTargetMatches(rule.getTargetId(), product);
                    case CATEGORY -> taxonomyTargetMatches(rule.getTargetId(), product);
                    case GLOBAL -> true;
                };

        if (!targetMatch) {
            return false;
        }

        return switch (rule.getConditionType()) {
            case NONE -> true;
            case LOCATION ->
                request.getLocationId() != null
                        && request.getLocationId().toString().equals(rule.getConditionValue());
            case CUSTOMER_TIER ->
                request.getCustomerTier() != null && request.getCustomerTier().equals(rule.getConditionValue());
        };
    }

    private boolean skuTargetMatches(UUID targetId, ProductEntity product) {
        return targetId != null && product != null && targetId.equals(product.getId());
    }

    private boolean taxonomyTargetMatches(UUID targetId, ProductEntity product) {
        if (targetId == null || product == null) {
            return false;
        }

        Set<UUID> taxonomyNodeIds = new HashSet<>();
        if (product.getCategory() != null && product.getCategory().getId() != null) {
            taxonomyNodeIds.add(product.getCategory().getId());
        }
        if (product.getSubcategory() != null && product.getSubcategory().getId() != null) {
            taxonomyNodeIds.add(product.getSubcategory().getId());
        }
        return taxonomyNodeIds.contains(targetId);
    }

    private List<UUID> resolveCandidatePriceBookIds(ResolvePriceRequestDto request) {
        if (request.getPriceBookId() != null) {
            return List.of(requirePriceBook(request.getPriceBookId()).getPriceBookId());
        }

        if (request.getLocationId() != null) {
            var locationBook = priceBookRepository.findByScopeAndScopeIdAndStatus(
                    PriceBookScope.LOCATION, request.getLocationId(), PriceBookStatus.ACTIVE);
            if (locationBook.isPresent()) {
                return List.of(locationBook.get().getPriceBookId());
            }
        }

        return priceBookRepository
                .findByScopeAndIsDefaultTrueAndStatus(PriceBookScope.COMPANY_DEFAULT, PriceBookStatus.ACTIVE)
                .map(book -> List.of(book.getPriceBookId()))
                .orElseGet(List::of);
    }

    private void validatePriceBookRequest(PriceBookCreateRequestDto request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new CatalogValidationException("name is required.");
        }
        if (request.getScope() == null) {
            throw new CatalogValidationException("scope is required.");
        }
        if ((request.getScope() == PriceBookScope.LOCATION || request.getScope() == PriceBookScope.CUSTOMER_TIER)
                && request.getScopeId() == null) {
            throw new CatalogValidationException("scopeId is required for LOCATION and CUSTOMER_TIER scopes.");
        }
    }

    private void validateRuleRequest(PriceBookRuleCreateRequestDto request) {
        validateRuleTarget(request);
        validateRuleRequiredFields(request);
        validateRuleEffectiveWindow(request);
        validateRuleCondition(request);
    }

    private void validateRuleTarget(PriceBookRuleCreateRequestDto request) {
        if (request.getTargetType() == null) {
            throw new CatalogValidationException("targetType is required.");
        }
        if (request.getTargetType() != PriceBookRuleTargetType.GLOBAL && request.getTargetId() == null) {
            throw new CatalogValidationException("targetId is required for SKU and CATEGORY targets.");
        }
    }

    private void validateRuleRequiredFields(PriceBookRuleCreateRequestDto request) {
        if (request.getPricingLogic() == null || request.getPricingLogic().isBlank()) {
            throw new CatalogValidationException("pricingLogic is required.");
        }
        if (request.getEffectiveStartAt() == null) {
            throw new CatalogValidationException("effectiveStartAt is required.");
        }
        if (request.getCreatedByUserId() == null) {
            throw new CatalogValidationException("createdByUserId is required.");
        }
    }

    private void validateRuleEffectiveWindow(PriceBookRuleCreateRequestDto request) {
        if (request.getEffectiveEndAt() != null
                && request.getEffectiveEndAt().isBefore(request.getEffectiveStartAt())) {
            throw new CatalogValidationException("effectiveEndAt must be on or after effectiveStartAt.");
        }
    }

    private void validateRuleCondition(PriceBookRuleCreateRequestDto request) {
        PriceBookRuleConditionType conditionType = request.getConditionType();
        if (conditionType == PriceBookRuleConditionType.LOCATION
                || conditionType == PriceBookRuleConditionType.CUSTOMER_TIER) {
            if (request.getConditionValue() == null
                    || request.getConditionValue().isBlank()) {
                throw new CatalogValidationException(
                        "conditionValue is required for LOCATION and CUSTOMER_TIER conditionType.");
            }

            if (conditionType == PriceBookRuleConditionType.LOCATION) {
                try {
                    UUID.fromString(request.getConditionValue());
                } catch (IllegalArgumentException ex) {
                    throw new CatalogValidationException(
                            "conditionValue must be a valid UUID for LOCATION conditionType.");
                }
            }
        }
    }

    private void ensureRuleNoConflict(UUID priceBookId, PriceBookRuleCreateRequestDto request, UUID excludeRuleId) {
        PriceBookRuleConditionType conditionType =
                request.getConditionType() == null ? PriceBookRuleConditionType.NONE : request.getConditionType();

        Instant windowEnd = request.getEffectiveEndAt() == null
                ? FAR_FUTURE_EFFECTIVE_END_AT
                : toInstant(request.getEffectiveEndAt());

        var conflicts = priceBookRuleRepository.findConflicts(
                priceBookId,
                request.getTargetType(),
                request.getTargetId(),
                conditionType,
                request.getConditionValue(),
                toInstant(request.getEffectiveStartAt()),
                windowEnd,
                excludeRuleId);

        if (!conflicts.isEmpty()) {
            throw new CatalogBusinessRuleException(
                    "Price book rule conflicts with an existing rule in overlapping dates.");
        }
    }

    private PriceBookEntity requirePriceBook(UUID priceBookId) {
        return priceBookRepository
                .findById(priceBookId)
                .orElseThrow(() -> new CatalogNotFoundException("PriceBook not found: " + priceBookId));
    }

    private PriceBookRuleEntity requireRule(UUID priceBookId, UUID ruleId) {
        PriceBookRuleEntity entity = priceBookRuleRepository
                .findById(ruleId)
                .orElseThrow(() -> new CatalogNotFoundException("PriceBook rule not found: " + ruleId));
        if (entity.getPriceBook() == null
                || !entity.getPriceBook().getPriceBookId().equals(priceBookId)) {
            throw new CatalogNotFoundException("PriceBook rule not found for priceBookId=" + priceBookId);
        }
        return entity;
    }

    private PriceBookDto toPriceBookDto(PriceBookEntity entity) {
        PriceBookDto dto = new PriceBookDto();
        dto.setPriceBookId(entity.getPriceBookId());
        dto.setName(entity.getName());
        dto.setScope(entity.getScope());
        dto.setScopeId(entity.getScopeId());
        dto.setDefault(entity.isDefault());
        dto.setStatus(entity.getStatus());
        dto.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private PriceBookRuleDto toPriceBookRuleDto(PriceBookRuleEntity entity) {
        PriceBookRuleDto dto = new PriceBookRuleDto();
        dto.setRuleId(entity.getRuleId());
        dto.setPriceBookId(
                entity.getPriceBook() == null ? null : entity.getPriceBook().getPriceBookId());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setPricingLogic(entity.getPricingLogic());
        dto.setConditionType(entity.getConditionType());
        dto.setConditionValue(entity.getConditionValue());
        dto.setPriority(entity.getPriority());
        dto.setEffectiveStartAt(toOffsetDateTime(entity.getEffectiveStartAt()));
        dto.setEffectiveEndAt(toOffsetDateTime(entity.getEffectiveEndAt()));
        dto.setStatus(entity.getStatus());
        dto.setCreatedByUserId(entity.getCreatedByUserId());
        dto.setCreatedAt(toOffsetDateTime(entity.getCreatedAt()));
        dto.setUpdatedAt(toOffsetDateTime(entity.getUpdatedAt()));
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private Instant toInstant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private OffsetDateTime toOffsetDateTime(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private record PricePayload(BigDecimal amount, String currency) {}
}
