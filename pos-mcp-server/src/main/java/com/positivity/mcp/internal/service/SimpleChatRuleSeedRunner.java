package com.positivity.mcp.internal.service;

import com.positivity.mcp.internal.entity.SimpleChatRule;
import com.positivity.mcp.internal.orchestration.SimpleChatRuleDefaults;
import com.positivity.mcp.internal.orchestration.SimpleChatRuleSpec;
import com.positivity.mcp.internal.repository.SimpleChatRuleRepository;
import java.util.HashSet;
import java.util.Set;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class SimpleChatRuleSeedRunner implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleChatRuleSeedRunner.class);

    private final SimpleChatRuleRepository repository;

    public SimpleChatRuleSeedRunner(@NonNull SimpleChatRuleRepository repository) {
        this.repository = repository;
    }

    @Override
    public void run(@NonNull ApplicationArguments args) {
        Set<String> existingKeys = new HashSet<>();
        repository.findAll().forEach(rule -> existingKeys.add(key(rule.getRuleType().name(), rule.getPhrase())));

        int inserted = 0;
        for (SimpleChatRuleSpec ruleSpec : SimpleChatRuleDefaults.defaults()) {
            String key = key(ruleSpec.ruleType().name(), ruleSpec.phrase());
            if (existingKeys.contains(key)) {
                continue;
            }
            try {
                SimpleChatRule rule = new SimpleChatRule();
                rule.setRuleType(ruleSpec.ruleType());
                rule.setPhrase(ruleSpec.phrase());
                rule.setLanguageCode(ruleSpec.languageCode());
                rule.setPriority(ruleSpec.priority());
                rule.setEnabled(true);
                repository.save(rule);
                existingKeys.add(key);
                inserted++;
            } catch (Exception exception) {
                LOGGER.warn(
                        "Failed to seed simple chat rule type={} phrase={}",
                        ruleSpec.ruleType(),
                        ruleSpec.phrase(),
                        exception);
            }
        }

        if (inserted > 0) {
            LOGGER.info("Seeded {} simple chat classifier rules", inserted);
        }
    }

    private static @NonNull String key(@NonNull String ruleType, @NonNull String phrase) {
        return ruleType + "|" + phrase;
    }
}
