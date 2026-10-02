---
rag_id: accounting.sales-analytics
rag_scope: accounting
required_permissions:
  - reporting:view:financial-statements
  - accounting:analytics:view
  - invoice:analytics:view
---

## Purpose

RAG id: accounting.sales-analytics
RAG scope: accounting
Required permissions: reporting:view:financial-statements, accounting:analytics:view, invoice:analytics:view
Audience: owners, managers and accounting staff asking for sales, revenue, margin, profit or spend figures.
This document is reference context only and grants no access; each figure is gated at request time by the permission named beside it.

It defines the money analytics behind questions such as "What's our gross margin?", "Show revenue by month",
"How did Q3 compare to Q2?" and "What did we spend with Michelin in 2025?": which platform report answers each one,
on what basis the figure is computed, and what the platform does not compute. Money figures belong to accounting;
operational KPIs (work in progress, cycle time, utilization) are in the reporting metrics guide
(`reporting.metrics`).

General definitions marked [n] come from the external sources listed under Sources; everything said about what a
report returns comes from the platform's own code and API specifications.

## Where each figure comes from

| Figure | Assistant tool | Backing endpoint | Permission | Basis |
| --- | --- | --- | --- | --- |
| Revenue, expenses, net income for a window | `getSalesReport` (Reporting), `getFinancialSummary` (Accounting) | pos-accounting `GET /v1/accounting/reports/financial/income-statement` | `reporting:view:financial-statements` | Posted journal activity on the accounts mapped to the income statement |
| Revenue plus open receivables | `getRevenueReport` (Reporting) | income statement, then aged receivables as of the window end | `reporting:view:financial-statements` | As above; the receivables section is today's open balance, not the window's |
| Revenue per customer, top customers by revenue | `getRevenueByCustomer` (Invoice) | pos-invoice `GET /v1/invoices/analytics/revenue-by-customer` | `invoice:analytics:view` | Invoice totals, tax included, by invoice creation date |
| Spend per vendor | `getVendorSpend` (Accounting) | pos-accounting `GET /v1/accounting/analytics/vendor-spend` | `accounting:analytics:view` | Settled A/P cash, plus bills issued in the window |
| Invoiced versus collected | discovered operation `getCollectionsAnalytics` | pos-accounting `GET /v1/accounting/analytics/collections` | `accounting:analytics:view` | Finalized invoice totals against payments applied, cash received and refunds |

Every one of these takes a single window (`startDate`, `endDate`, inclusive ISO dates) and returns one aggregate for
it. Resolve the window first with `resolveDateWindow`, or `resolveNamedPeriod` when the question names the period
outright, and pass the dates through unchanged. `resolveNamedPeriod` takes `YYYY`, `YYYY-MM` or `YYYY-Qn` only
("2025", "2026-07", "2026-Q3"): a quarter named without a year ("Q3") needs a year first (see Period comparisons).

## Revenue and sales: definitions

In everyday usage "sales" and "revenue" are the same thing: revenue from a business's main activities "may also be
referred to as sales or as turnover" [2]. Net sales are total revenue less sales returns, allowances and discounts
[3]. Under IFRS 15 revenue is recognised as the promised goods or services are transferred to the customer, at the
consideration the business expects to be entitled to (paragraph 2), and that amount excludes "amounts collected on
behalf of third parties (for example, some sales taxes)" (paragraph 47) [1]. These are general definitions; they do
not mean the platform implements the standard.

## Revenue: the platform's three figures

The platform has three revenue figures and they are not interchangeable:

- **Income statement revenue** (`totalRevenue` and the revenue lines of `lineItems`). When an invoice is finalized,
  pos-accounting posts `Dr Accounts Receivable` for the invoice total, `Cr Service Revenue` for the total less tax and
  `Cr Sales Tax Payable` for the tax, dated at the invoice's finalization. Reverting or cancelling a finalized
  invoice posts the mirror entry, and a credit memo debits revenue. Deposit-take invoices and zero-total invoices
  post nothing: a deposit is a liability, not a sale. So this figure is accrual revenue, net of tax and of posted
  credit memos, in the period the invoice was finalized. It is the platform's answer to "what was our revenue" or
  "total sales".
- **Revenue by customer** (`revenue` per row). The sum of the invoice `total` (subtotal plus adjustments plus tax)
  of each customer's FINALIZED or POSTED invoices whose creation date falls in the window, with deposit-take
  invoices excluded and window days taken in UTC. It includes sales tax, is not reduced by credit memos or refunds,
  and is dated by invoice creation, not finalization. Rows are ranked by revenue and capped at 20; `truncated`
  says when more customers had revenue. Use it to rank customers, not to state the business's revenue.
- **Invoiced** (collections analytics). The sum of invoice totals, tax included, for invoices finalized in the
  window, deposit-take invoices excluded. It is the billed side of a collection rate, not revenue.

When an answer quotes a revenue figure, name the basis in one phrase ("posted revenue, before tax" or "invoice
totals including tax"). Do not add the three together or compare one with another as if they measured the same
thing. A quotation or estimate amount is never revenue, and a DRAFT invoice has not been billed.

## Margin, markup and profit: definitions

Gross profit is net sales less the cost of goods sold, an amount; gross margin expresses it "as a percentage of net
sales" [4]. Cost of goods sold is the cost of creating what was sold; in a service business it is "the labor, payroll
taxes, and benefits of those people who generate billable hours" [5], so a shop's gross margin needs both the parts
cost and the technicians' labor cost. Net margin also takes off "all other expenses not related to the cost of goods
sold" [4].

Margin and markup are often confused. Margin is measured on the selling price, markup on the cost: a part bought for
$70 and sold for $100 has a $30 margin, which is 30% of the price, and a $30 markup, which is 42.9% of the cost [6].
When a user quotes a percentage, ask or state which one is meant. The catalog's price-override guardrail
(`minMarginPercent`) computes (override price - cost) / override price x 100: a margin on the selling price, never a
markup.

## Margin and profit: what the platform computes

- **Net income** on the income statement: `totalRevenue` minus `totalExpenses`. Each statement line is the posted
  balance of the GL accounts mapped to it, combined by the mapping's operation (`SUM`, `SUBTRACT` or `NEGATE`).
  `totalRevenue` adds the lines whose code the service classes as revenue (codes beginning `REVENUE_` or
  `PL_REVENUE_`, or containing `INCOME`); `totalExpenses` adds those it classes as expense (codes beginning
  `EXPENSE_`, `PL_EXPENSE_` or `PL_EXPENSES_`, or containing `COST`). The figures therefore depend on how the
  tenant's statement-line mapping is set up, and the shipped seed maps only account 4000 Service Revenue. If
  `totalRevenue` is zero while a revenue line in `lineItems` carries an amount, or a revenue line is negative,
  report the line as it is, say the statement totals are not configured for it, and do not present `netIncome` as
  profit. The seeded revenue line's code and sign are tracked as a defect in
  louisburroughs/durion-positivity-backend#2394.
- **Gross margin is not computed.** The income statement has no gross-profit or cost-of-goods-sold field, and no
  platform flow posts the cost of parts sold when an invoice is finalized: the only automated posting to 5000 Cost of
  Goods Sold is the inventory cost revaluation. Parts cost lives in pos-inventory, and a customer-margin report is a
  planned, unbuilt item of the analytics plan. Asked for gross margin, say the platform does not calculate it and
  offer revenue and net income for the period instead.
- **Profit by product or category is not computed.** No report breaks revenue or profit down by product, category
  or line type, so "the profit on tire sales in March" cannot be answered. Say so; posted revenue for the window is
  the closest figure.
- **Price-override margin is a different thing.** The catalog's location guardrail (`minMarginPercent`) checks one
  override price against an item cost before it is approved. It is a pricing control on one price, not the business's
  margin, and must not be quoted as one.

## Period comparisons

A period comparison measures the same figure in two periods and reports the dollar change and the percentage
change, the dollar change divided by the earlier (base) period's amount [7]. A sequential comparison (this quarter
against the last one) shows recent movement but carries the seasonal swing between the two; a year-over-year
comparison (against the same quarter a year earlier) is the usual way to keep seasonality out when the data is not
seasonally adjusted [8]. A tire shop's winter-tire season makes the difference matter: say which kind of comparison
the answer is.

- Make one call per period with the same tool, so both figures share a basis. "How did Q3 compare to Q2?" is two
  income statements, one per quarter, each resolved with `resolveNamedPeriod` as `YYYY-Qn`.
- A quarter named without a year: take the most recent occurrence of the later-named quarter that has already
  ended, and the earlier-named quarter immediately before it. On 2026-10-02, "Q3 compared to Q2" is `2026-Q3` against
  `2026-Q2`; on 2026-08-15 it is `2025-Q3` against `2025-Q2`, because 2026-Q3 has not ended. Name both quarters with
  their years in the answer so the user can correct the choice. When the quarters named are not consecutive and the
  intended years are not clear ("Q4 versus Q1"), ask which years are meant instead of choosing. Never pass a bare
  `Q3` to `resolveNamedPeriod`, and never invent a year without stating it.
- Report both figures, the absolute difference (later minus earlier) and the change as a percentage of the earlier
  figure. When the earlier figure is zero the percentage is undefined: report the absolute change and say that a
  percentage change is not meaningful against a zero base. When the earlier figure is negative (a net loss), give
  the absolute change and describe the direction in words rather than quoting a percentage, whose sign would
  mislead. When the earlier figure is small, a small dollar change becomes a large percentage (from $200 to $1,000
  is +400%): always give the dollar amounts beside the percentage.

## Trends

- No report buckets by month or week: a `groupBy` parameter is planned and does not exist. A trend ("revenue by
  month for six months", "sales trend for the past 12 weeks") therefore needs one call per bucket. The collections
  analytics endpoint states that looping it across more than three periods exceeds its call budget; for longer
  series, say the platform has no single trend report, and offer the total for the whole window or up to three
  periods.
- Aged receivables and payables cannot build a trend: a past as-of date ages today's open balances, it does not
  reconstruct the balance on that date.
- An in-progress month or quarter is not comparable with a complete one; say when the latest period is partial.

## Vendor spend

Spend analysis is "the process of collection, classifying and analysing expenditure data", used for spend
visibility, compliance and control [9]. On this platform the one spend report is per vendor and per window:

- `paidAmount`: the gross amount of A/P payments to the vendor whose payment date falls in the window and whose
  gateway status shows the cash moved (`GATEWAY_SUCCEEDED` or a later GL-posting status). This is cash paid.
- `billsIssuedInWindow` and `avgIssuedBillAmount`: the vendor's bills dated in the window, regardless of payment
  status, and their average total. These are a different population from `paidAmount`, so the two never
  reconcile, and the bill-side figures must not be called paid.
- Rows are ranked by `paidAmount` and capped at 20 through the assistant (the endpoint itself accepts up to 100);
  `truncated` says when more vendors had activity. The tool takes no vendor filter: to answer for one vendor, find
  its row by name, and if it is not among the rows returned while `truncated` is true, say that it may be outside
  the top 20 rather than reporting zero.
- The business glossary defines "what did we spend with" as posted A/P vendor-bill amounts net of vendor credits.
  No endpoint returns that figure: the bill side here is gross and is not netted against vendor credits. Report
  `paidAmount` as cash paid in the window and, where useful, the bills issued, and name the difference from the
  glossary definition instead of presenting either as that figure.

## Top customers

"Top customers by revenue" and "largest customers" rank customers by revenue: use revenue by customer
(`getRevenueByCustomer`) and state its basis (invoice totals including tax, by invoice creation date). The glossary
defines "best customers" by contribution margin, which needs costs the platform does not compute, so it cannot be
answered as defined: say so, and offer the revenue ranking as the largest customers. "Who are our top customers?"
names no metric; ask which one is meant.

## Common questions

| Question | How to answer |
| --- | --- |
| "What's our gross margin?" | Not computed (no cost of goods sold on sales). Offer revenue and net income for a period, noting that net income depends on the statement-line mapping. |
| "Show sales trend for the past 12 weeks" | No weekly buckets. Offer the 12-week total, or up to three shorter periods. |
| "How did Q3 compare to Q2?" | Choose the years (latest ended Q3 and the Q2 before it) and state them; two income statements via `resolveNamedPeriod` `YYYY-Qn`; revenue, net income, the difference, and the percentage change unless the earlier figure is zero or negative. |
| "What did we spend with Michelin in 2025?" | `getVendorSpend` for 2025; the vendor's `paidAmount` (cash paid) and bills issued; mention the top-20 cap and the glossary difference. |
| "Show revenue by month" | One income statement per month, up to three months; beyond that, the total for the window. |
| "What was the profit on tire sales between March 1 and March 31?" | Profit by product is not computed; offer posted revenue and net income for March. |

## Sources

Platform sources:

- `pos-accounting/openapi.yaml` (`generateIncomeStatement`, `getVendorSpend`, `getCollectionsAnalytics`, the
  `IncomeStatementReport`, `VendorSpendReport`, `VendorSpendRow` and `CollectionsAnalyticsReport` schemas)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/FinancialReportingServiceImpl.java`
  (statement lines, revenue and expense classification, totals)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/repository/JournalEntryRepository.java`
  (`sumPostedBalanceForAccount`)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/AccountingAnalyticsServiceImpl.java`
  (settled A/P payment statuses)
- `pos-accounting/src/main/java/com/positivity/accounting/internal/service/GLPostingService.java` (credit memo
  reversal entry)
- `pos-accounting/src/main/resources/db/migration/R__seed_reference_accounting.sql` (account 4000 statement mapping,
  posting categories, 5000 Cost of Goods Sold)
- `pos-accounting/src/main/resources/permissions.yaml`
- `pos-invoice/openapi.yaml` (`getRevenueByCustomer`, `RevenueByCustomerReport`, `RevenueByCustomerRow`)
- `pos-invoice/src/main/java/com/positivity/invoice/internal/repository/InvoiceRepository.java`
  (`revenueByCustomer`), `internal/service/InvoiceAnalyticsServiceImpl.java` (revenue statuses, UTC window) and
  `internal/service/InvoiceFinalizationServiceImpl.java` (invoice total = subtotal + adjustments + tax)
- `pos-invoice/src/main/resources/permissions.yaml`
- `pos-catalog/openapi.yaml` (location guardrail policy, `minMarginPercent`) and
  `pos-catalog/src/main/java/com/positivity/catalog/internal/service/LocationPriceOverrideServiceImpl.java`
  (`calculateMarginPercent`)
- `pos-mcp-server/src/main/java/com/positivity/mcp/internal/orchestration/tools/ReportingFacadeTool.java`,
  `AccountingFacadeTool.java`, `InvoiceFacadeTool.java`, `BusinessGlossary.java`, `DateWindowFacadeTool.java`
  (`resolveNamedPeriod` accepts `YYYY`, `YYYY-MM`, `YYYY-Qn`)
- `durion/domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md` (invoice revenue recognition entry and
  reversal)
- `durion/domains/general/mcp-server/analytics-capability-plan.md` (decision D2 on customer margin, decision D8 on
  deposit-take invoices, W3.1 `groupBy`)

External sources (accessed 2026-10-02):

1. IFRS 15 "Revenue from Contracts with Customers", paragraphs 2 and 47, IFRS Foundation (International Accounting
   Standards Board).
   <https://www.ifrs.org/content/dam/ifrs/publications/html-standards/english/2024/issued/ifrs15.html>
2. "Revenue", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Revenue>
3. Steven Bragg, "Net sales definition", AccountingTools. <https://www.accountingtools.com/articles/net-sales>
4. Steven Bragg, "Gross margin definition", AccountingTools. <https://www.accountingtools.com/articles/gross-margin>
5. Steven Bragg, "Cost of goods sold definition", AccountingTools.
   <https://www.accountingtools.com/articles/cost-of-goods-sold>
6. Steven Bragg, "The difference between margin and markup", AccountingTools.
   <https://www.accountingtools.com/articles/what-is-the-difference-between-margin-and-markup.html>
7. Mitchell Franklin, Patty Graybeal and Dixon Cooper, "A Financial Statement Analysis", Principles of
   Accounting, Volume 1: Financial Accounting, OpenStax (Rice University).
   <https://openstax.org/books/principles-financial-accounting/pages/a-financial-statement-analysis>
8. "Seasonal adjustment", Wikipedia, Wikimedia Foundation. <https://en.wikipedia.org/wiki/Seasonal_adjustment>
9. "Spend Analysis - What is Spend Analysis", Chartered Institute of Procurement & Supply (CIPS).
   <https://www.cips.org/intelligence-hub/finance/spend-analysis>
