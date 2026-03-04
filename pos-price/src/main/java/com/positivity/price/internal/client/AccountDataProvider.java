package com.positivity.price.internal.client;

import java.util.Optional;
import java.util.UUID;

public interface AccountDataProvider {
    Optional<AccountContext> getAccountContext(UUID accountId);
}
