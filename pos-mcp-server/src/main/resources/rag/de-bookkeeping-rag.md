# Double-Entry Bookkeeping & Core Accounting Principles

_For Use in Durion Positivity ETSMS RAG Knowledge Base_

---

## 1. Purpose

This document provides a foundational reference for double-entry bookkeeping and essential accounting principles. It is designed to support a natural-language interface (NLI) in:

- Interpreting user financial queries
- Generating correct journal entries
- Explaining financial states and discrepancies
- Enforcing domain rules within the accounting module

This document complements the accounting module README and serves as conceptual grounding for reasoning over financial data.

---

## 2. Accounting Roles and Built-In Permissions

The accounting module in `pos-security-service` currently exposes the following built-in accounting personas. These roles are cumulative in places: `AP_CLERK` includes the `GL_ANALYST` set, `ACCOUNTANT` includes `AP_CLERK`, and `CONTROLLER` includes `ACCOUNTANT`. `ACCOUNT_MANAGER` is aligned more closely with invoice and billing operations than pure ledger administration.

| Role                   | Typical focus                              | Built-in permissions                                                                                                                                                                                                                                    |
| ---------------------- | ------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `ACCOUNTING_ASSOCIATE` | Day-to-day accounting operations           | `accounting:coa:view`, `accounting:mapping:view`, `accounting:posting_rules:view`, `accounting:je:view`, `accounting:events:view`, `accounting:export:view`, `accounting:ap:view`, `accounting:ap:approve`, `accounting:ap:reject`, `accounting:ap:pay` |
| `GL_ANALYST`           | GL setup, mappings, and draft entries      | `accounting:coa:view/create/edit`, `accounting:mapping:view/create/edit`, `accounting:posting_rules:view/create`, `accounting:je:view/create`, `accounting:events:view/submit`, `accounting:export:view`, `accounting:ap:view`                          |
| `AP_CLERK`             | Accounts payable processing                | All `GL_ANALYST` permissions plus `accounting:ap:approve`, `accounting:ap:reject`, `accounting:ap:pay`                                                                                                                                                  |
| `ACCOUNTANT`           | Posting, reversals, and accounting control | All `AP_CLERK` permissions plus `accounting:coa:deactivate`, `accounting:mapping:deactivate`, `accounting:posting_rules:publish`, `accounting:je:post`, `accounting:je:reverse`, `accounting:events:retry`, `accounting:events:reprocess`               |
| `CONTROLLER`           | Highest built-in accounting authority      | All `ACCOUNTANT` permissions plus `accounting:posting_rules:archive`, `accounting:export:request`                                                                                                                                                       |
| `ACCOUNT_MANAGER`      | Commercial account and invoice operations  | All `ACCOUNTANT` permissions plus `invoice:manage`, `invoice:billing-rules`                                                                                                                                                                             |

Every built-in accounting role also receives `mcp:chat:execute`, which allows the MCP/NLI layer to respond within the user’s security context.

---

## 3. Fundamental Accounting Equation

At the core of all accounting systems:

**Assets = Liabilities + Equity**

This equation must always remain balanced. Every transaction affects at least two accounts to preserve this equality.

---

## 4. Double-Entry Bookkeeping

### Definition

Double-entry bookkeeping records every financial transaction with **at least two entries**:

- A **debit** (DR)
- A **credit** (CR)

Total debits must always equal total credits.

---

## 5. Account Types and Normal Balances

| Account Type    | Description                        | Normal Balance |
| --------------- | ---------------------------------- | -------------- |
| **Assets**      | Resources owned (cash, inventory)  | Debit          |
| **Liabilities** | Obligations owed (loans, payables) | Credit         |
| **Equity**      | Owner’s residual interest          | Credit         |
| **Revenue**     | Income from operations             | Credit         |
| **Expenses**    | Costs incurred to generate revenue | Debit          |

---

## 6. Debit vs Credit Rules

| Account Type | Increase | Decrease |
| ------------ | -------- | -------- |
| Assets       | Debit    | Credit   |
| Liabilities  | Credit   | Debit    |
| Equity       | Credit   | Debit    |
| Revenue      | Credit   | Debit    |
| Expenses     | Debit    | Credit   |

---

## 7. Transaction Structure

Each transaction:

- Has a **date**
- Includes one or more **debit entries**
- Includes one or more **credit entries**
- Must balance:
  **Sum(Debits) = Sum(Credits)**

### Example

**Scenario:** Purchase inventory for $1,000 cash

| Account           | Debit | Credit |
| ----------------- | ----- | ------ |
| Inventory (Asset) | 1,000 |        |
| Cash (Asset)      |       | 1,000  |

---

## 8. Journal Entries

A **journal entry** is the atomic unit of accounting.

### Structure

- Header (date, description, reference)
- Line items:
  - Account
  - Amount
  - Debit or Credit indicator

### Rules

- Minimum two lines
- Must balance
- Immutable once posted (corrections require new entries)

---

## 9. Ledger and Posting

- **General Ledger (GL):** Master record of all accounts
- Each account has a running balance
- Journal entries are **posted** to the ledger

---

## 10. Trial Balance

A validation step:

- Lists all accounts and balances
- Confirms:

  **Total Debits = Total Credits**

Used to detect errors before financial statements are generated.

---

## 11. Financial Statements

### 11.1 Balance Sheet

Snapshot at a point in time:

- Assets
- Liabilities
- Equity

### 11.2 Income Statement

Performance over a period:

- Revenue
- Expenses
- Net Income = Revenue − Expenses

### 11.3 Cash Flow Statement

Tracks movement of cash:

- Operating activities
- Investing activities
- Financing activities

---

## 12. Accrual vs Cash Accounting

### Accrual Accounting (Preferred)

- Revenue recognized when earned
- Expenses recognized when incurred
- Independent of cash movement

### Cash Accounting

- Recognized only when cash changes hands
- Simpler but less accurate

---

## 13. Core Principles

### 13.1 Consistency

Use the same accounting methods over time.

### 13.2 Conservatism

Avoid overstating assets or income.

### 13.3 Matching Principle

Expenses should match the revenue they generate.

### 13.4 Revenue Recognition

Recognize revenue when earned, not when paid.

### 13.5 Materiality

Focus on information that impacts decision-making.

---

## 14. Accounting Constraints for System Design

### 14.1 Immutability

- Posted entries should not be edited
- Corrections must be reversing entries

### 14.2 Auditability

- Every transaction must be traceable
- Include timestamps, user, and source

### 14.3 Idempotency

- Duplicate transaction submissions must not double-post

### 14.4 Referential Integrity

- All journal lines must reference valid accounts

---

## 15. Domain-Specific Considerations (Positivity / ETSMS)

### 15.1 Common Events

- Workorder completion → Revenue + Receivable
- Parts consumption → Inventory reduction + COGS
- Payment received → Cash increase + Receivable decrease
- Vendor invoice → Expense + Payable

---

### 15.2 Example: Workorder Invoice

| Account             | Debit | Credit |
| ------------------- | ----- | ------ |
| Accounts Receivable | 500   |        |
| Revenue             |       | 500    |

---

### 15.3 Example: Payment Received

| Account             | Debit | Credit |
| ------------------- | ----- | ------ |
| Cash                | 500   |        |
| Accounts Receivable |       | 500    |

---

## 16. Natural Language Interpretation Guidelines

The NLI should:

### Identify:

- **Intent** (invoice, payment, adjustment)
- **Entities** (customer, workorder, product)
- **Amounts**
- **Timing**

### Map to:

- Correct accounts
- Proper debit/credit structure
- Valid journal entry

### Validate:

- Balanced entries
- Account compatibility
- Business rules

---

## 17. Error Handling Patterns

### Common Errors

- Unbalanced entries
- Invalid account usage
- Missing counterpart entry
- Duplicate transaction

### Handling Strategy

- Reject invalid entries before posting
- Provide corrective guidance
- Suggest balancing entries if possible

---

## 18. Summary

Double-entry bookkeeping ensures:

- Mathematical integrity of financial data
- Traceability of all transactions
- Consistent financial reporting

The system must enforce:

- Balance (debits = credits)
- Immutability
- Auditability
- Clear mapping between business events and accounting outcomes

---

## 19. Suggested Extensions (Future RAG Enhancements)

- Chart of Accounts (COA) definitions
- Industry-specific accounting rules (fleet service, tire dealers)
- Tax handling logic (sales tax, jurisdictional rules)
- Cost accounting (labor vs parts breakdown)
- Event-to-entry mapping registry

---
