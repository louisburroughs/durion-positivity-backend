package com.positivity.price.internal.client;

import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnMissingBean(value = AccountDataProvider.class, ignored = StubAccountDataProvider.class)
public class StubAccountDataProvider implements AccountDataProvider {
    @Override
    public Optional<AccountContext> getAccountContext(UUID accountId) {
        return Optional.empty();
    }
}
