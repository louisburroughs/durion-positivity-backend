package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ShopManagerFacadeTool {

    private final RestClient restClient;
    private final String shopStatusUriTemplate;
    private final String shopQueueUriTemplate;
    private final String shopSearchUriTemplate;

    public ShopManagerFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.shopmanager.base-url}") @NonNull String baseUrl,
            @Value("${pos.shopmanager.status-uri-template}") @NonNull String shopStatusUriTemplate,
            @Value("${pos.shopmanager.queue-uri-template}") @NonNull String shopQueueUriTemplate,
            @Value("${pos.shopmanager.search-uri-template}") @NonNull String shopSearchUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.shopStatusUriTemplate = shopStatusUriTemplate;
        this.shopQueueUriTemplate = shopQueueUriTemplate;
        this.shopSearchUriTemplate = shopSearchUriTemplate;
    }

    @Tool("Get overall status for a shop location")
    public String getShopStatus(@P("The shop ID") @NonNull String shopId) {
        return restClient
                .get()
                .uri(shopStatusUriTemplate, Map.of("shopId", shopId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get the active queue and workflow load for a shop")
    public String getShopQueue(@P("The shop ID") @NonNull String shopId) {
        return restClient
                .get()
                .uri(shopQueueUriTemplate, Map.of("shopId", shopId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search shops by name, region, status, or manager")
    public String searchShops(@P("Search query for shops") @NonNull String query) {
        return restClient
                .get()
                .uri(shopSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }
}
