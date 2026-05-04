package com.positivity.mcp.internal.orchestration.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.client.RestClient;

@Component
public class AccountingFacadeTool {

    private final RestClient restClient;
    private final String accountBalanceUriTemplate;
    private final String journalEntriesSearchUriTemplate;
    private final String financialSummaryUriTemplate;

    public AccountingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.accounting.base-url}") @NonNull String baseUrl,
            @Value("${pos.accounting.account-balance-uri-template}") @NonNull String accountBalanceUriTemplate,
            @Value("${pos.accounting.journal-entries-search-uri-template}") @NonNull String journalEntriesSearchUriTemplate,
            @Value("${pos.accounting.financial-summary-uri-template}") @NonNull String financialSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.accountBalanceUriTemplate = accountBalanceUriTemplate;
        this.journalEntriesSearchUriTemplate = journalEntriesSearchUriTemplate;
        this.financialSummaryUriTemplate = financialSummaryUriTemplate;
    }

    @Tool("Get account balance information by account ID")
    public String getAccountBalance(@P("The account ID") @NonNull String accountId) {
        return restClient
                .get()
                .uri(accountBalanceUriTemplate, Map.of("accountId", accountId))
                .retrieve()
                .body(String.class);
    }

    @Tool("Search accounting journal entries using text or filter terms")
    public String searchJournalEntries(@P("Search query for journal entries") @NonNull String query) {
        return restClient
                .get()
                .uri(journalEntriesSearchUriTemplate, Map.of("query", query))
                .retrieve()
                .body(String.class);
    }

    @Tool("Get a financial summary for a requested accounting period")
    public String getFinancialSummary(@P("Accounting period identifier") @NonNull String period) {
        return restClient
                .get()
                .uri(financialSummaryUriTemplate, Map.of("period", period))
                .retrieve()
                .body(String.class);
    }
}
