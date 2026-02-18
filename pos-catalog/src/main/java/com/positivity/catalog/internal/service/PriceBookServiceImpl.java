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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PriceBookServiceImpl implements PriceBookService {

    private static final String PRICE_BASE_DATA_MISSING = "PRICE_BASE_DATA_MISSING";

    private final PriceBookRepository priceBookRepository;
    private final PriceBookRuleRepository priceBookRuleRepository;
    private final ProductRepository productRepository;
    private final ProductMsrpRepository productMsrpRepository;

    public PriceBookServiceImpl(
            PriceBookRepository priceBookRepository,
            PriceBookRuleRepository priceBookRuleRepository,
            ProductRepository productRepository,
            ProductMsrpRepository productMsrpRepository) {
        this.priceBookRepository = priceBookRepository;
        this.priceBookRuleRepository = priceBookRuleRepository;
        this.productRepository = productRepository;
        this.productMsrpRepository = productMsrpRepository;
    }

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
        entity.setEffectiveStartAt(request.getEffectiveStartAt());
        entity.setEffectiveEndAt(request.getEffectiveEndAt());
        entity.setStatus(PriceBookRuleStatus.ACTIVE);
        entity.setCreatedByUserId(request.getCreatedByUserId());

        return toPriceBookRuleDto(priceBookRuleRepository.save(entity));
    }

    @Override
    @Transactional
    public PriceBookRuleDto updateRule(
            @NonNull UUID priceBookId,
            @NonNull UUID ruleId,
            @NonNull PriceBookRuleCreateRequestDto request) {
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
        entity.setEffectiveStartAt(request.getEffectiveStartAt());
        entity.setEffectiveEndAt(request.getEffectiveEndAt());
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
        return priceBookRuleRepository.findAllByPriceBookId(priceBookId)
                .stream()
                .map(this::toPriceBookRuleDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ResolvePriceResponseDto resolvePrice(@NonNull ResolvePriceRequestDto request) {
        if (request.getProductId() == null) {
            throw new CatalogValidationException("productId is required.");
        }

        ProductEntity product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new CatalogNotFoundException("Product not found: " + request.getProductId()));

        LocalDate asOfDate = request.getAsOf() == null ? LocalDate.now() : request.getAsOf();
        OffsetDateTime asOf = asOfDate.atStartOfDay().atOffset(ZoneOffset.UTC);

        List<UUID> priceBookIds = resolveCandidatePriceBookIds(request);
        if (!priceBookIds.isEmpty()) {
            var candidates = priceBookRuleRepository
                    .findActiveRulesForBooks(priceBookIds, PriceBookRuleStatus.ACTIVE, asOf)
                    .stream()
                    .filter(rule -> isRuleApplicable(rule, request, product))
                    .toList();

            var winner = pickWinner(candidates, product);
            if (winner != null) {
                return fromRule(winner);
            }
        }

        return productMsrpRepository.findActive(request.getProductId(), asOfDate)
                .map(msrp -> {
                    ResolvePriceResponseDto dto = new ResolvePriceResponseDto();
                    dto.setResolvedAmount(msrp.getAmount().setScale(4, RoundingMode.HALF_UP).toPlainString());
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

    private ResolvePriceResponseDto fromRule(PriceBookRuleEntity rule) {
        PricePayload payload = parsePricePayload(rule.getPricingLogic());
        ResolvePriceResponseDto dto = new ResolvePriceResponseDto();
        dto.setResolvedAmount(payload.amount().setScale(4, RoundingMode.HALF_UP).toPlainString());
        dto.setCurrency(payload.currency());
        dto.setSource(ResolvePriceSource.PRICE_BOOK_RULE);
        dto.setSourceRuleId(rule.getRuleId());
        return dto;
    }

    private PricePayload parsePricePayload(String pricingLogic) {
        String compact = pricingLogic.replace(" ", "");
        String amountToken = extractJsonValue(compact, "amount");
        if (amountToken == null) {
            amountToken = extractJsonValue(compact, "fixedAmount");
        }
        if (amountToken == null) {
            throw new CatalogValidationException("pricingLogic must include amount or fixedAmount.");
        }
        String currencyToken = extractJsonValue(compact, "currency");
        if (currencyToken == null) {
            currencyToken = "USD";
        }
        BigDecimal amount = new BigDecimal(amountToken);
        return new PricePayload(amount, currencyToken);
    }

    private String extractJsonValue(String json, String field) {
        String quoted = "\"" + field + "\"";
        int fieldIndex = json.indexOf(quoted);
        if (fieldIndex < 0) {
            return null;
        }
        int colonIndex = json.indexOf(':', fieldIndex + quoted.length());
        if (colonIndex < 0) {
            return null;
        }
        int startIndex = colonIndex + 1;
        if (startIndex >= json.length()) {
            return null;
        }

        if (json.charAt(startIndex) == '"') {
            int endQuote = json.indexOf('"', startIndex + 1);
            if (endQuote < 0) {
                return null;
            }
            return json.substring(startIndex + 1, endQuote);
        }

        int end = startIndex;
        while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
            end++;
        }
        return json.substring(startIndex, end);
    }

    private PriceBookRuleEntity pickWinner(List<PriceBookRuleEntity> candidates, ProductEntity product) {
        if (candidates.isEmpty()) {
            return null;
        }

        List<PriceBookRuleEntity> mutable = new ArrayList<>(candidates);
        mutable.sort(Comparator
                .comparingInt((PriceBookRuleEntity r) -> precedenceScore(r, product)).reversed()
                .thenComparing(PriceBookRuleEntity::getPriority, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(PriceBookRuleEntity::getEffectiveStartAt,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(r -> r.getRuleId().toString()));
        return mutable.get(0);
    }

    private int precedenceScore(PriceBookRuleEntity rule, ProductEntity product) {
        if (rule.getTargetType() == PriceBookRuleTargetType.SKU && rule.getTargetId() != null
                && rule.getTargetId().equals(product.getId())) {
            return 3;
        }
        if (rule.getTargetType() == PriceBookRuleTargetType.CATEGORY && rule.getTargetId() != null
                && product.getCategory() != null && rule.getTargetId().equals(product.getCategory().getId())) {
            return 2;
        }
        if (rule.getTargetType() == PriceBookRuleTargetType.GLOBAL) {
            return 1;
        }
        return 0;
    }

    private boolean isRuleApplicable(PriceBookRuleEntity rule, ResolvePriceRequestDto request, ProductEntity product) {
        boolean targetMatch = switch (rule.getTargetType()) {
            case SKU -> rule.getTargetId() != null && rule.getTargetId().equals(request.getProductId());
            // TODO(CAP-167): replace direct category-id equality with taxonomy-aware traversal when category hierarchy data is available.
            case CATEGORY -> rule.getTargetId() != null && product.getCategory() != null
                    && rule.getTargetId().equals(product.getCategory().getId());
            case GLOBAL -> true;
        };

        if (!targetMatch) {
            return false;
        }

        return switch (rule.getConditionType()) {
            case NONE -> true;
            case LOCATION -> request.getLocationId() != null
                    && request.getLocationId().toString().equals(rule.getConditionValue());
            case CUSTOMER_TIER -> request.getCustomerTier() != null
                    && request.getCustomerTier().equals(rule.getConditionValue());
        };
    }

    private List<UUID> resolveCandidatePriceBookIds(ResolvePriceRequestDto request) {
        if (request.getPriceBookId() != null) {
            return List.of(requirePriceBook(request.getPriceBookId()).getPriceBookId());
        }

        if (request.getLocationId() != null) {
            var locationBook = priceBookRepository.findByScopeAndScopeIdAndStatus(
                    PriceBookScope.LOCATION,
                    request.getLocationId(),
                    PriceBookStatus.ACTIVE);
            if (locationBook.isPresent()) {
                return List.of(locationBook.get().getPriceBookId());
            }
        }

        return priceBookRepository.findByScopeAndIsDefaultTrueAndStatus(
                PriceBookScope.COMPANY_DEFAULT,
                PriceBookStatus.ACTIVE)
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
        if (request.getScope() == PriceBookScope.LOCATION && request.getScopeId() == null) {
            throw new CatalogValidationException("scopeId is required for LOCATION scope.");
        }
    }

    private void validateRuleRequest(PriceBookRuleCreateRequestDto request) {
        if (request.getTargetType() == null) {
            throw new CatalogValidationException("targetType is required.");
        }
        if (request.getTargetType() != PriceBookRuleTargetType.GLOBAL && request.getTargetId() == null) {
            throw new CatalogValidationException("targetId is required for SKU and CATEGORY targets.");
        }
        if (request.getPricingLogic() == null || request.getPricingLogic().isBlank()) {
            throw new CatalogValidationException("pricingLogic is required.");
        }
        if (request.getEffectiveStartAt() == null) {
            throw new CatalogValidationException("effectiveStartAt is required.");
        }
        if (request.getEffectiveEndAt() != null
                && request.getEffectiveEndAt().isBefore(request.getEffectiveStartAt())) {
            throw new CatalogValidationException("effectiveEndAt must be on or after effectiveStartAt.");
        }
        if (request.getCreatedByUserId() == null) {
            throw new CatalogValidationException("createdByUserId is required.");
        }

        PriceBookRuleConditionType conditionType = request.getConditionType();
        if (conditionType == PriceBookRuleConditionType.LOCATION) {
            if (request.getConditionValue() == null || request.getConditionValue().isBlank()) {
                throw new CatalogValidationException("conditionValue is required for LOCATION conditionType.");
            }
            try {
                UUID.fromString(request.getConditionValue());
            } catch (IllegalArgumentException ex) {
                throw new CatalogValidationException(
                        "conditionValue must be a valid UUID for LOCATION conditionType.");
            }
        } else if (conditionType == PriceBookRuleConditionType.CUSTOMER_TIER) {
            if (request.getConditionValue() == null || request.getConditionValue().isBlank()) {
                throw new CatalogValidationException("conditionValue is required for CUSTOMER_TIER conditionType.");
            }
        }
    }

    private void ensureRuleNoConflict(UUID priceBookId, PriceBookRuleCreateRequestDto request, UUID excludeRuleId) {
        PriceBookRuleConditionType conditionType = request.getConditionType() == null ? PriceBookRuleConditionType.NONE
                : request.getConditionType();

        OffsetDateTime windowEnd = request.getEffectiveEndAt() == null ? OffsetDateTime.parse("9999-12-31T23:59:59Z")
                : request.getEffectiveEndAt();

        var conflicts = priceBookRuleRepository.findConflicts(
                priceBookId,
                request.getTargetType(),
                request.getTargetId(),
                conditionType,
                request.getConditionValue(),
                request.getEffectiveStartAt(),
                windowEnd,
                excludeRuleId);

        if (!conflicts.isEmpty()) {
            throw new CatalogBusinessRuleException(
                    "Price book rule conflicts with an existing rule in overlapping dates.");
        }
    }

    private PriceBookEntity requirePriceBook(UUID priceBookId) {
        return priceBookRepository.findById(priceBookId)
                .orElseThrow(() -> new CatalogNotFoundException("PriceBook not found: " + priceBookId));
    }

    private PriceBookRuleEntity requireRule(UUID priceBookId, UUID ruleId) {
        PriceBookRuleEntity entity = priceBookRuleRepository.findById(ruleId)
                .orElseThrow(() -> new CatalogNotFoundException("PriceBook rule not found: " + ruleId));
        if (!entity.getPriceBook().getPriceBookId().equals(priceBookId)) {
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
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private PriceBookRuleDto toPriceBookRuleDto(PriceBookRuleEntity entity) {
        PriceBookRuleDto dto = new PriceBookRuleDto();
        dto.setRuleId(entity.getRuleId());
        dto.setPriceBookId(entity.getPriceBook().getPriceBookId());
        dto.setTargetType(entity.getTargetType());
        dto.setTargetId(entity.getTargetId());
        dto.setPricingLogic(entity.getPricingLogic());
        dto.setConditionType(entity.getConditionType());
        dto.setConditionValue(entity.getConditionValue());
        dto.setPriority(entity.getPriority());
        dto.setEffectiveStartAt(entity.getEffectiveStartAt());
        dto.setEffectiveEndAt(entity.getEffectiveEndAt());
        dto.setStatus(entity.getStatus());
        dto.setCreatedByUserId(entity.getCreatedByUserId());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        dto.setVersion(entity.getVersion());
        return dto;
    }

    private record PricePayload(BigDecimal amount, String currency) {
    }
}
