package com.positivity.price.internal.service;

import com.positivity.price.dto.RestrictionEvaluationItem;
import com.positivity.price.dto.RestrictionEvaluationRequest;
import com.positivity.price.dto.RestrictionEvaluationResult;
import com.positivity.price.internal.entity.RestrictionRule;
import com.positivity.price.enums.EvaluationContext;
import com.positivity.price.enums.RestrictionDecision;
import com.positivity.price.internal.exception.RestrictionServiceUnavailableException;
import com.positivity.price.internal.repository.RestrictionRuleRepository;
import com.positivity.price.service.RestrictionEvaluationService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class RestrictionEvaluationServiceImpl implements RestrictionEvaluationService {

    private final RestrictionRuleRepository repository;
    private final long evaluationTimeoutMs;

    @Autowired
    public RestrictionEvaluationServiceImpl(RestrictionRuleRepository repository) {
        this(repository, 800L);
    }

    RestrictionEvaluationServiceImpl(RestrictionRuleRepository repository, long evaluationTimeoutMs) {
        this.repository = repository;
        this.evaluationTimeoutMs = evaluationTimeoutMs;
    }

    @Override
    public @NonNull List<RestrictionEvaluationResult> evaluate(@NonNull RestrictionEvaluationRequest request) {
        return request.items().stream()
                .map(this::evaluateItem)
                .toList();
    }

    private RestrictionEvaluationResult evaluateItem(RestrictionEvaluationItem item) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<DecisionWithRules> future = CompletableFuture
                    .supplyAsync(() -> resolveDecision(item), executor)
                    .orTimeout(evaluationTimeoutMs, TimeUnit.MILLISECONDS);
            try {
                DecisionWithRules dwr = future.join();
                return new RestrictionEvaluationResult(item.productId(), dwr.decision(), dwr.ruleIds(), List.of());
            } catch (CompletionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                return handleFailure(item.productId(), item.context(), cause);
            }
        }
    }

    private DecisionWithRules resolveDecision(RestrictionEvaluationItem item) {
        List<RestrictionRule> rules = repository.findByProductIdAndActiveTrueAndLocationTagAndServiceTag(
                item.productId(), item.locationTag(), item.serviceTag());
        if (rules.isEmpty()) {
            return new DecisionWithRules(RestrictionDecision.ALLOW, List.of());
        }
        List<UUID> ruleIds = rules.stream().map(RestrictionRule::getRuleId).toList();
        boolean hasBlock = rules.stream().anyMatch(rule -> !rule.isOverrideable());
        if (hasBlock) {
            return new DecisionWithRules(RestrictionDecision.BLOCK, ruleIds);
        }
        return new DecisionWithRules(RestrictionDecision.ALLOW_WITH_OVERRIDE, ruleIds);
    }

    private RestrictionEvaluationResult handleFailure(UUID productId, EvaluationContext context, Throwable cause) {
        if (isCommitPath(context)) {
            throw new RestrictionServiceUnavailableException("Evaluation failed on commit path", cause);
        }
        return new RestrictionEvaluationResult(productId, RestrictionDecision.RESTRICTION_UNKNOWN, List.of(), List.of());
    }

    private boolean isCommitPath(EvaluationContext context) {
        return context == EvaluationContext.CHECKOUT
                || context == EvaluationContext.INVOICE_FINALIZE
                || context == EvaluationContext.COMMIT_SALE;
    }

    private record DecisionWithRules(RestrictionDecision decision, List<UUID> ruleIds) {
    }
}