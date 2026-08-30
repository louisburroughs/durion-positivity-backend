package com.positivity.mcp.internal.orchestration.tools;

import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ExaWebSearchTool {

    private static final Logger logger = LoggerFactory.getLogger(ExaWebSearchTool.class);

    private final RestClient restClient;
    private final String apiKey;
    private final String searchType;
    private final int maxResults;

    public ExaWebSearchTool(
            RestClient.Builder restClientBuilder,
            @Value("${exa.base-url:https://api.exa.ai}") @NonNull String baseUrl,
            @Value("${exa.api-key:}") @NonNull String apiKey,
            @Value("${exa.search-type:auto}") @NonNull String searchType,
            @Value("${exa.max-results:5}") int maxResults) {
        this.restClient = ToolRestClientSupport.instrumentedBuilder(restClientBuilder)
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();
        this.apiKey = apiKey;
        this.searchType = searchType;
        this.maxResults = maxResults;
    }

    @Tool(
            description = "Search the web for current information about automotive parts, "
                    + "industry news, product specifications, or general knowledge. Returns a bounded set of "
                    + "top results (default 5).")
    public @NonNull String webSearch(@ToolParam(description = "The search query") @NonNull String query) {
        if (apiKey.isBlank()) {
            logger.warn("EXA_API_KEY is not configured; skipping web search for query='{}'", query);
            return "Web search is currently unavailable because EXA_API_KEY is not configured.";
        }

        var body = Map.of(
                "query", query,
                "type", searchType,
                "numResults", maxResults,
                "text", Map.of("maxCharacters", 8000));

        String response = restClient
                .post()
                .uri("/search")
                .header("x-api-key", apiKey)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);

        return response == null ? "No web search results returned." : response;
    }
}
