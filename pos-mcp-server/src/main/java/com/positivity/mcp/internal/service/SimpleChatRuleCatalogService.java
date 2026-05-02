package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.orchestration.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.orchestration.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.orchestration.SimpleChatRuleSpec;
import com.positivity.mcp.internal.repository.SimpleChatRuleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class SimpleChatRuleCatalogService {

    private final SimpleChatRuleRepository repository;
    private final Clock clock;
    private final Duration cacheTtl;

    private final AtomicReference<CatalogSnapshot> snapshotRef = new AtomicReference<>();

    public SimpleChatRuleCatalogService(
            @NonNull SimpleChatRuleRepository repository,
            @NonNull Clock clock,
            @Value("${mcp.simple-chat.rule-cache-ttl-minutes:5}") long cacheTtlMinutes) {
        this.repository = repository;
        this.clock = clock;
        this.cacheTtl = Duration.ofMinutes(Math.max(1, cacheTtlMinutes));
    }

    public @NonNull SimpleChatRuleCatalog currentCatalog() {
        CatalogSnapshot current = snapshotRef.get();
        Instant now = Instant.now(clock);
        if (current != null && current.expiresAt().isAfter(now)) {
            return current.catalog();
        }

        synchronized (this) {
            CatalogSnapshot refreshed = snapshotRef.get();
            Instant recheckNow = Instant.now(clock);
            if (refreshed != null && refreshed.expiresAt().isAfter(recheckNow)) {
                return refreshed.catalog();
            }
            CatalogSnapshot loaded = loadSnapshot(recheckNow);
            snapshotRef.set(loaded);
            return loaded.catalog();
        }
    }

    private @NonNull CatalogSnapshot loadSnapshot(@NonNull Instant now) {
        List<SimpleChatRuleSpec> persistedRules = repository.findByEnabledTrueOrderByPriorityAscPhraseAsc().stream()
                .map(rule -> new SimpleChatRuleSpec(
                        rule.getRuleType(), rule.getPhrase(), rule.getLanguageCode(), rule.getPriority()))
                .toList();
        List<SimpleChatRuleSpec> effectiveRules =
                persistedRules.isEmpty() ? SimpleChatRuleDefaults.defaults() : persistedRules;
        return new CatalogSnapshot(SimpleChatRuleCatalog.from(effectiveRules), now.plus(cacheTtl));
    }

    private record CatalogSnapshot(@NonNull SimpleChatRuleCatalog catalog, @NonNull Instant expiresAt) {}
}
