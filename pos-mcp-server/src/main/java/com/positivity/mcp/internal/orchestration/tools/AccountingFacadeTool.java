package com.positivity.mcp.internal.orchestration.tools;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
    private final String incomeStatementUriTemplate;
    private final String balanceSheetUriTemplate;
    private final String trialBalanceUriTemplate;
    private final String agedReceivablesUriTemplate;
    private final String agedPayablesUriTemplate;

    public AccountingFacadeTool(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.accounting.base-url}") @NonNull String baseUrl,
            @Value("${pos.accounting.account-balance-uri-template}") @NonNull String accountBalanceUriTemplate,
            @Value("${pos.accounting.general-ledger-uri-template}") @NonNull String generalLedgerUriTemplate,
            @Value("${pos.accounting.income-statement-uri-template}") @NonNull String incomeStatementUriTemplate,
            @Value("${pos.accounting.balance-sheet-uri-template}") @NonNull String balanceSheetUriTemplate,
            @Value("${pos.accounting.trial-balance-uri-template}") @NonNull String trialBalanceUriTemplate,
            @Value("${pos.accounting.aged-receivables-uri-template}") @NonNull String agedReceivablesUriTemplate,
            @Value("${pos.accounting.aged-payables-uri-template}") @NonNull String agedPayablesUriTemplate) {
        this.restClient = ToolRestClientSupport.instrumentedClient(restClientBuilder, baseUrl);
        this.accountBalanceUriTemplate = accountBalanceUriTemplate;
        this.generalLedgerUriTemplate = generalLedgerUriTemplate;
        this.incomeStatementUriTemplate = incomeStatementUriTemplate;
        this.balanceSheetUriTemplate = balanceSheetUriTemplate;
        this.trialBalanceUriTemplate = trialBalanceUriTemplate;
        this.agedReceivablesUriTemplate = agedReceivablesUriTemplate;
        this.agedPayablesUriTemplate = agedPayablesUriTemplate;
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

    @Tool(
            description = "Get a composed financial summary for an accounting period. period must be a "
                    + "calendar month in YYYY-MM form (e.g. 2026-05) or a calendar year in YYYY form (e.g. "
                    + "2026). Returns a JSON envelope with three sections: incomeStatement (revenue and "
                    + "expense activity over the period), balanceSheet (assets, liabilities and equity as of "
                    + "the period's end date), and trialBalance (per-account debit/credit proof as of the "
                    + "period's end date). A section the caller is not authorized for, or that fails, is "
                    + "reported in that section's status without failing the whole summary; the top-level "
                    + "status is degraded when the income statement is unavailable.")
    public String getFinancialSummary(
            @ToolParam(description = "Accounting period: YYYY-MM or YYYY") @NonNull String period) {
        ReportingPeriods.DateRange range = ReportingPeriods.toDateRange(period);
        return ToolComposition.named("financialSummary")
                .call(
                        "incomeStatement",
                        () -> restClient
                                .get()
                                .uri(
                                        incomeStatementUriTemplate,
                                        Map.of("startDate", range.startDate(), "endDate", range.endDate()))
                                .retrieve()
                                .body(String.class))
                .require("incomeStatement")
                .call(
                        "balanceSheet",
                        () -> restClient
                                .get()
                                .uri(balanceSheetUriTemplate, Map.of("asOfDate", range.endDate()))
                                .retrieve()
                                .body(String.class))
                .call(
                        "trialBalance",
                        () -> restClient
                                .get()
                                .uri(trialBalanceUriTemplate, Map.of("asOf", range.endDate()))
                                .retrieve()
                                .body(String.class))
                .render();
    }

    @Tool(
            description = "Get the Aged Receivables report: the platform's per-customer accounts-receivable "
                    + "(A/R) aging aggregate as of a date. Returns one row per customer with an open "
                    + "balance — customerId, current (0-30 days), days31To60, days61To90, days90Plus, "
                    + "totalOutstanding — plus grand totals across all rows. Use this for past-due, "
                    + "outstanding-balance, and customer-concentration questions instead of aggregating "
                    + "individual invoices. The customerName field is always null on this report; resolve "
                    + "names separately with the customer directory if the answer needs them. Age is "
                    + "measured from invoice creation, NOT from the invoice due date, so \"60 days past "
                    + "due\" here means 60 days since the invoice was raised. IMPORTANT — a past asOfDate "
                    + "does NOT reconstruct the historical balance: each invoice contributes its CURRENT "
                    + "open balance, only the aging buckets are keyed to asOfDate. Do not use a series of "
                    + "past dates to build an A/R balance trend; the earlier points would restate today's "
                    + "balances. Rows are empty when no open receivables exist as of the date.")
    public String getAgedReceivables(
            @ToolParam(
                            description = "As-of date, YYYY-MM-DD. Buckets age against this date; balances "
                                    + "are always current, never historical.")
                    @NonNull
                    String asOfDate) {
        return restClient
                .get()
                .uri(agedReceivablesUriTemplate, Map.of("asOfDate", validatedAsOfDate(asOfDate)))
                .retrieve()
                .body(String.class);
    }

    @Tool(
            description = "Get the Aged Payables report: the platform's per-vendor accounts-payable (A/P) "
                    + "aging aggregate as of a date. Returns one row per vendor with an open balance — "
                    + "vendorId, vendorName, current (0-30 days), days31To60, days61To90, days90Plus, "
                    + "totalOutstanding — plus grand totals across all rows. Use this for past-due, "
                    + "outstanding-balance, and vendor-concentration questions about what the business "
                    + "owes, instead of aggregating individual vendor bills. IMPORTANT — a past asOfDate "
                    + "does NOT reconstruct the historical balance: each bill contributes its CURRENT open "
                    + "balance, only the aging buckets are keyed to asOfDate. Do not use a series of past "
                    + "dates to build an A/P balance trend; the earlier points would restate today's "
                    + "balances. Rows are empty when no open payables exist as of the date.")
    public String getAgedPayables(
            @ToolParam(
                            description = "As-of date, YYYY-MM-DD. Buckets age against this date; balances "
                                    + "are always current, never historical.")
                    @NonNull
                    String asOfDate) {
        return restClient
                .get()
                .uri(agedPayablesUriTemplate, Map.of("asOfDate", validatedAsOfDate(asOfDate)))
                .retrieve()
                .body(String.class);
    }

    /**
     * Validates the LLM-supplied {@code asOfDate} is a real ISO date before any request is built;
     * anything else is rejected with a message stating the accepted form so the model can
     * self-correct (mirrors the {@link ReportingPeriods} validation style).
     */
    private static @NonNull String validatedAsOfDate(@NonNull String asOfDate) {
        String trimmed = asOfDate.trim();
        try {
            LocalDate.parse(trimmed);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "Invalid asOfDate '" + asOfDate + "': pass an ISO date in YYYY-MM-DD form (e.g. 2026-06-30)",
                    exception);
        }
        return trimmed;
    }
}
