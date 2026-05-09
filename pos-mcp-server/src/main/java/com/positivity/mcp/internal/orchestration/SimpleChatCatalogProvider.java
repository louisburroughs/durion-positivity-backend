package com.positivity.mcp.internal.orchestration;

import com.positivity.mcp.internal.classification.SimpleChatRuleCatalog;
import com.positivity.mcp.internal.service.SimpleChatRuleCatalogService;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
final class SimpleChatCatalogProvider {

    private final SimpleChatRuleCatalogService catalogService;

    @Autowired
    SimpleChatCatalogProvider(@NonNull SimpleChatRuleCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @NonNull
    SimpleChatRuleCatalog currentCatalog() {
        return catalogService.currentCatalog();
    }
}
