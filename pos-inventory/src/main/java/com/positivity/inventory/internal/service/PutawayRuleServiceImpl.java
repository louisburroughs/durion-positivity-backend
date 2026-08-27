package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.service.PutawayRuleService;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link PutawayRuleService}. */
@Service
@RequiredArgsConstructor
public class PutawayRuleServiceImpl implements PutawayRuleService {

    private static final String RULE_RESOURCE = "PutawayRule";

    /** Listing order mirrors resolution order so the list reads as "what the matcher will try". */
    private static final Comparator<PutawayRule> RESOLUTION_ORDER = Comparator.comparingInt(
                    (PutawayRule rule) -> PutawayRuleMatchType.precedence()
                            .indexOf(rule.getMatchType() == null ? PutawayRuleMatchType.ANY : rule.getMatchType()))
            .thenComparingInt(rule -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority());

    private final PutawayRuleRepository putawayRuleRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PutawayRuleResponse> listRules() {
        return putawayRuleRepository.findAll().stream()
                .sorted(RESOLUTION_ORDER)
                .map(PutawayRuleServiceImpl::toResponse)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull PutawayRuleResponse getRule(@NonNull String ruleId) {
        return toResponse(findRule(parseRuleId(ruleId)));
    }

    @Override
    @Transactional
    public @NonNull PutawayRuleResponse createRule(@NonNull PutawayRuleRequest request) {
        boolean enabled = request.getIsEnabled() == null || request.getIsEnabled();
        enforceSingleEnabledAnyRule(request.getMatchType(), enabled, null);

        PutawayRule rule = PutawayRule.builder()
                .priority(request.getPriority())
                .matchType(request.getMatchType())
                .matchValue(normaliseMatchValue(request))
                .destinationLocationId(request.getDestinationLocationId())
                .destinationStrategy(
                        request.getDestinationStrategy() == null
                                ? PutawayDestinationStrategy.FIXED
                                : request.getDestinationStrategy())
                .isEnabled(enabled)
                .build();

        return toResponse(putawayRuleRepository.save(rule));
    }

    @Override
    @Transactional
    public @NonNull PutawayRuleResponse updateRule(@NonNull String ruleId, @NonNull PutawayRuleRequest request) {
        UUID parsedRuleId = parseRuleId(ruleId);
        PutawayRule rule = findRule(parsedRuleId);

        // Full replacement everywhere else, but an omitted isEnabled keeps the rule's current
        // state rather than defaulting to true. The documented way to retire a rule is to disable
        // it, so defaulting an omitted flag to "on" would mean a later PUT that only bumps the
        // priority silently puts a deliberately disabled routing rule back in front of every
        // matching line. Create still defaults to enabled, which is the useful default for a rule
        // somebody just wrote.
        boolean enabled = request.getIsEnabled() == null ? rule.isEnabled() : request.getIsEnabled();
        enforceSingleEnabledAnyRule(request.getMatchType(), enabled, parsedRuleId);

        rule.setPriority(request.getPriority());
        rule.setMatchType(request.getMatchType());
        rule.setMatchValue(normaliseMatchValue(request));
        rule.setDestinationLocationId(request.getDestinationLocationId());
        rule.setDestinationStrategy(
                request.getDestinationStrategy() == null
                        ? PutawayDestinationStrategy.FIXED
                        : request.getDestinationStrategy());
        rule.setEnabled(enabled);

        return toResponse(putawayRuleRepository.save(rule));
    }

    @Override
    @Transactional
    public void deleteRule(@NonNull String ruleId) {
        putawayRuleRepository.delete(findRule(parseRuleId(ruleId)));
    }

    /**
     * At most one enabled {@code ANY} rule may exist.
     *
     * <p>{@code ANY} is the terminal tier and matches every line, so the first one the priority order
     * reaches always wins and a second enabled one is unreachable by construction. Allowing it would
     * let an operator author a fallback, see it accepted, and never see it used — the same class of
     * silent-no-op the dead {@code criteria} column was. Disabled {@code ANY} rules are unrestricted,
     * because {@code isEnabled} is what makes a rule reachable in the first place.
     *
     * @param excludedRuleId the rule being updated, so it does not conflict with itself
     */
    private void enforceSingleEnabledAnyRule(
            @Nullable PutawayRuleMatchType matchType, boolean enabled, @Nullable UUID excludedRuleId) {
        if (matchType != PutawayRuleMatchType.ANY || !enabled) {
            return;
        }
        putawayRuleRepository.findByMatchTypeAndIsEnabledTrue(PutawayRuleMatchType.ANY).stream()
                .filter(existing -> !existing.getRuleId().equals(excludedRuleId))
                .findFirst()
                .ifPresent(existing -> {
                    throw new DuplicateEnabledAnyPutawayRuleException(existing.getRuleId());
                });
    }

    /**
     * Blank is stored as null so that "no value" has one representation. The bean validation on
     * {@link PutawayRuleRequest} has already established that a value is present exactly when the
     * tier needs one and that it parses as a UUID; this only canonicalises it.
     */
    private static @Nullable String normaliseMatchValue(PutawayRuleRequest request) {
        String matchValue = request.getMatchValue();
        if (matchValue == null || matchValue.isBlank()) {
            return null;
        }
        return matchValue.trim();
    }

    private PutawayRule findRule(UUID ruleId) {
        return putawayRuleRepository
                .findById(ruleId)
                .orElseThrow(() -> new ResourceNotFoundException(RULE_RESOURCE, ruleId.toString()));
    }

    private static UUID parseRuleId(String ruleId) {
        try {
            return UUID.fromString(ruleId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ruleId must be a valid UUID", e);
        }
    }

    private static PutawayRuleResponse toResponse(PutawayRule rule) {
        return PutawayRuleResponse.builder()
                .ruleId(rule.getRuleId() != null ? rule.getRuleId().toString() : null)
                .priority(rule.getPriority())
                .matchType(rule.getMatchType() != null ? rule.getMatchType().name() : null)
                .matchValue(rule.getMatchValue())
                .destinationLocationId(
                        rule.getDestinationLocationId() != null
                                ? rule.getDestinationLocationId().toString()
                                : null)
                .destinationStrategy(
                        rule.getDestinationStrategy() != null
                                ? rule.getDestinationStrategy().name()
                                : null)
                .isEnabled(rule.isEnabled())
                .createdAt(rule.getCreatedAt())
                .updatedAt(rule.getUpdatedAt())
                .build();
    }
}
