package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.service.SimpleChatRuleCatalogService;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

@Component
final class SimpleChatCatalogProvider {

    private final SimpleChatRuleCatalogService catalogService;

    SimpleChatCatalogProvider(@NonNull SimpleChatRuleCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @NonNull
    SimpleChatRuleCatalog currentCatalog() {
        return catalogService.currentCatalog();
    }
}
