package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AccountingFacadeTool {

    private final RestClient restClient;

    public AccountingFacadeTool(
            RestClient.Builder restClientBuilder,
            @Value("${pos.accounting.base-url:http://pos-accounting/v1/accounting}") @NonNull String baseUrl) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
    }

    @Tool("Get account balance information by account ID")
    public String getAccountBalance(@P("The account ID") @NonNull String accountId) {
        return restClient
                .get()
                .uri("/accounts/{accountId}/balance", accountId)
                .retrieve()
                .body(String.class);
    }

    @Tool("Search accounting journal entries using text or filter terms")
    public String searchJournalEntries(@P("Search query for journal entries") @NonNull String query) {
        return restClient
                .get()
                .uri(uriBuilder -> uriBuilder
                        .path("/journal-entries/search")
                        .queryParam("q", query)
                        .build())
                .retrieve()
                .body(String.class);
    }

    @Tool("Get a financial summary for a requested accounting period")
    public String getFinancialSummary(@P("Accounting period identifier") @NonNull String period) {
        return restClient.get().uri("/summary/{period}", period).retrieve().body(String.class);
    }
}
