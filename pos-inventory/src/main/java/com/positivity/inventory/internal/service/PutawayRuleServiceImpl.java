package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.dto.putaway.PutawayRuleRequest;
import com.positivity.inventory.internal.dto.putaway.PutawayRuleResponse;
import com.positivity.inventory.internal.entity.PutawayRule;
import com.positivity.inventory.internal.enums.PutawayDestinationStrategy;
import com.positivity.inventory.internal.enums.PutawayRuleMatchType;
import com.positivity.inventory.internal.exception.DuplicateEnabledAnyPutawayRuleException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.PutawayRuleRepository;
import com.positivity.inventory.service.PutawayRuleService;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Default {@link PutawayRuleService}. */
@Service
@RequiredArgsConstructor
public class PutawayRuleServiceImpl implements PutawayRuleService {

    private static final String RULE_RESOURCE = "PutawayRule";

    /** Constraint added by {@code V44__putaway_rule_single_enabled_any.sql}, lower-cased for matching. */
    private static final String SINGLE_ENABLED_ANY_CONSTRAINT = "putaway_rule_single_enabled_any";

    /** Listing order mirrors resolution order so the list reads as "what the matcher will try". */
    private static final Comparator<PutawayRule> RESOLUTION_ORDER = Comparator.comparingInt(
                    (PutawayRule rule) -> PutawayRuleMatchType.precedence()
                            .indexOf(rule.getMatchType() == null ? PutawayRuleMatchType.ANY : rule.getMatchType()))
            .thenComparingInt(rule -> rule.getPriority() == null ? Integer.MAX_VALUE : rule.getPriority());

    private final PutawayRuleRepository putawayRuleRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

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
        requireResolvableDestination(request.getDestinationLocationId(), enabled);

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

        return toResponse(saveEnforcingSingleEnabledAny(rule));
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
        requireResolvableDestination(request.getDestinationLocationId(), enabled);

        rule.setPriority(request.getPriority());
        rule.setMatchType(request.getMatchType());
        rule.setMatchValue(normaliseMatchValue(request));
        rule.setDestinationLocationId(request.getDestinationLocationId());
        rule.setDestinationStrategy(
                request.getDestinationStrategy() == null
                        ? PutawayDestinationStrategy.FIXED
                        : request.getDestinationStrategy());
        rule.setEnabled(enabled);

        return toResponse(saveEnforcingSingleEnabledAny(rule));
    }

    @Override
    @Transactional
    public void deleteRule(@NonNull String ruleId) {
        putawayRuleRepository.delete(findRule(parseRuleId(ruleId)));
    }

    /**
     * Saves the rule, translating the database's single-enabled-ANY constraint into the same 409 the
     * pre-flight check raises.
     *
     * <p>{@link #enforceSingleEnabledAnyRule} reads before writing, so two concurrent requests can
     * both find no enabled {@code ANY} rule and both proceed. The unique constraint on
     * {@code enabled_any_guard} is what actually decides (see
     * {@code V44__putaway_rule_single_enabled_any.sql}); the loser arrives here as a constraint
     * violation and must not surface as a 500, because from the caller's side it is the ordinary
     * conflict. The flush is explicit so the violation is thrown inside this method rather than at
     * commit, where it could no longer be attributed to this rule.
     */
    private PutawayRule saveEnforcingSingleEnabledAny(PutawayRule rule) {
        try {
            return putawayRuleRepository.saveAndFlush(rule);
        } catch (DataIntegrityViolationException ex) {
            if (isSingleEnabledAnyViolation(ex)) {
                throw DuplicateEnabledAnyPutawayRuleException.detectedByConstraint();
            }
            throw ex;
        }
    }

    /**
     * Whether this violation is the single-enabled-ANY constraint rather than some other integrity
     * failure, which must keep its own error rather than being reported as a rule conflict. Matched
     * on the constraint name, which the driver reports in the message on both PostgreSQL and H2.
     */
    private static boolean isSingleEnabledAnyViolation(DataIntegrityViolationException ex) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(SINGLE_ENABLED_ANY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
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
     * An enabled rule must target a storage location the {@code ext_storage_location} replica knows
     * (issue #1543).
     *
     * <p>Rules are the only putaway artifact authored ahead of use, and a destination typo surfaces
     * nowhere until execution: generation stamps the id onto tasks unchecked (deliberately — see the
     * upgrade-window note in {@code PutawayValidationServiceImpl#validatePutaway}), claiming
     * succeeds, and the first refusal is an operator with stock in hand. Alpha ran that way for
     * months with the terminal {@code ANY} rule aimed at a bin that never existed. Refusing here
     * moves the failure to the person editing the rule, who is the one who can fix it.
     *
     * <p>Two states pass without a lookup verdict. A <em>disabled</em> rule may say anything — it is
     * unreachable, and refusing it would block the documented retire-by-disabling path when a rule's
     * bin has since been decommissioned. And when the replica is <em>completely empty</em> the check
     * stands down: on a freshly provisioned environment pos-location's facts may not have arrived
     * yet, and refusing every rule until they do would turn a hydration lag into a configuration
     * outage. That window is advisory-covered by {@code PutawayRuleDestinationStartupCheck}. A
     * partially hydrated replica does refuse an unseen bin — by then absence is the best available
     * evidence the id is wrong, and the error names the replica so a race with a just-created
     * location is recognisable for what it is.
     */
    private void requireResolvableDestination(@Nullable UUID destinationLocationId, boolean enabled) {
        if (!enabled || destinationLocationId == null) {
            return;
        }
        if (extStorageLocationReplicaRepository.existsById(destinationLocationId)) {
            return;
        }
        if (extStorageLocationReplicaRepository.count() == 0) {
            return;
        }
        throw new IllegalArgumentException("Destination storage location " + destinationLocationId
                + " does not exist in the storage-location replica");
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
