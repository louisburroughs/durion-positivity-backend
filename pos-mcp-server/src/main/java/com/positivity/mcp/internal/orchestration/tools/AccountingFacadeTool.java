package com.positivity.mcp.internal.orchestration.tools;

import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AccountingFacadeTool {

    private final RestClient restClient;
    private final String accountBalanceUriTemplate;
    private final String generalLedgerUriTemplate;
    private final String financialSummaryUriTemplate;

    public AccountingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.accounting.base-url}") @NonNull String baseUrl,
            @Value("${pos.accounting.account-balance-uri-template}") @NonNull String accountBalanceUriTemplate,
            @Value("${pos.accounting.general-ledger-uri-template}") @NonNull String generalLedgerUriTemplate,
            @Value("${pos.accounting.financial-summary-uri-template}") @NonNull String financialSummaryUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.accountBalanceUriTemplate = accountBalanceUriTemplate;
        this.generalLedgerUriTemplate = generalLedgerUriTemplate;
        this.financialSummaryUriTemplate = financialSummaryUriTemplate;
    }

    @Tool(
            description = "Get the balance of a single general-ledger account by its GL account id, which must be "
                    + "a UUID (there is no lookup by account code or name). Returns the account's current balance "
                    + "details from the chart of accounts.")
    public String getAccountBalance(@ToolParam(description = "The GL account id (UUID)") @NonNull String glAccountId) {
        return restClient
                .get()
                .uri(accountBalanceUriTemplate, Map.of("glAccountId", glAccountId))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the general-ledger report (journal activity per account) for a date range. "
                    + "startDate and endDate are ISO dates in YYYY-MM-DD form; accountId optionally narrows the "
                    + "report to one GL account and must be a UUID when given. Use this for questions about "
                    + "journal entries or ledger activity — there is no free-text journal-entry search.")
    public String getGeneralLedger(
            @ToolParam(description = "Range start date, YYYY-MM-DD") @NonNull String startDate,
            @ToolParam(description = "Range end date, YYYY-MM-DD") @NonNull String endDate,
            @ToolParam(description = "Optional GL account id (UUID) to narrow the report", required = false)
                    String accountId) {
        String template = generalLedgerUriTemplate;
        Map<String, String> uriParams = new HashMap<>();
        uriParams.put("startDate", startDate);
        uriParams.put("endDate", endDate);
        if (accountId != null && !accountId.isBlank()) {
            template = template + "&accountId={accountId}";
            uriParams.put("accountId", accountId);
        }
        return restClient.get().uri(template, uriParams).retrieve().body(String.class);
    }

    @Tool(description = "Get a financial summary for a requested accounting period")
    public String getFinancialSummary(@ToolParam(description = "Accounting period identifier") @NonNull String period) {
        return restClient
                .get()
                .uri(financialSummaryUriTemplate, Map.of("period", period))
                .retrieve()
                .body(String.class);
    }
}
