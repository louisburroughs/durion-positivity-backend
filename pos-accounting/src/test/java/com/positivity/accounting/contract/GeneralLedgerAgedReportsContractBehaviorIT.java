package com.positivity.accounting.contract;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract behavior tests for the Story G2 (issue #960) reporting endpoints:
 * General Ledger, Aged Receivables, and Aged Payables, plus an export
 * round-trip. Each report is reconciled against persisted fixture data.
 */
@Transactional
class GeneralLedgerAgedReportsContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private VendorBillRepository vendorBillRepository;

    private static final UUID CASH_ACCOUNT_ID = UUID.fromString("20000000-0000-7000-8000-000000000001");
    private static final UUID CUSTOMER_ID = UUID.fromString("30000000-0000-7000-8000-000000000001");
    private static final UUID VENDOR_ID = UUID.fromString("40000000-0000-7000-8000-000000000001");

    private static final LocalDate PERIOD_START = LocalDate.of(2026, 6, 1);
    private static final LocalDate PERIOD_END = LocalDate.of(2026, 6, 30);
    private static final LocalDate AS_OF = LocalDate.of(2026, 6, 30);

    @Test
    @DisplayName("General Ledger reconciles opening/running/closing and excludes REVERSED originals")
    void generalLedgerReconciles() throws Exception {
        GLAccount cash = saveAccount(CASH_ACCOUNT_ID, "1000", "Cash - Operating");
        // Opening: POSTED activity strictly before the period.
        savePostedEntry("JE-202605-1", LocalDateTime.of(2026, 5, 15, 0, 0), cash, new BigDecimal("50000.00"), null);
        // In-period POSTED lines.
        savePostedEntry("JE-202606-1", LocalDateTime.of(2026, 6, 5, 0, 0), cash, new BigDecimal("30000.00"), null);
        savePostedEntry("JE-202606-2", LocalDateTime.of(2026, 6, 20, 0, 0), cash, null, new BigDecimal("5000.00"));
        // A REVERSED original in-period must be excluded (strict POSTED filter).
        saveEntry(
                "JE-202606-3",
                LocalDateTime.of(2026, 6, 10, 0, 0),
                cash,
                new BigDecimal("999.00"),
                null,
                JournalEntryStatus.REVERSED);

        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/general-ledger"))
                        .param("accountId", CASH_ACCOUNT_ID.toString())
                        .param("startDate", PERIOD_START.toString())
                        .param("endDate", PERIOD_END.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId").value(CASH_ACCOUNT_ID.toString()))
                .andExpect(jsonPath("$.accounts.length()").value(1))
                .andExpect(jsonPath("$.accounts[0].accountNumber").value("1000"))
                .andExpect(jsonPath("$.accounts[0].openingBalance").value(50000.00))
                // Only the two POSTED lines appear; the REVERSED 999 debit is excluded.
                .andExpect(jsonPath("$.accounts[0].lines.length()").value(2))
                .andExpect(jsonPath("$.accounts[0].lines[0].runningBalance").value(80000.00))
                .andExpect(jsonPath("$.accounts[0].lines[1].runningBalance").value(75000.00))
                .andExpect(jsonPath("$.accounts[0].totalDebit").value(30000.00))
                .andExpect(jsonPath("$.accounts[0].totalCredit").value(5000.00))
                .andExpect(jsonPath("$.accounts[0].closingBalance").value(75000.00))
                .andExpect(jsonPath("$.totalDebit").value(30000.00))
                .andExpect(jsonPath("$.totalCredit").value(5000.00));
    }

    @Test
    @DisplayName("General Ledger returns empty sections and zero totals when no POSTED activity exists")
    void generalLedgerEmpty() throws Exception {
        saveAccount(CASH_ACCOUNT_ID, "1000", "Cash - Operating");

        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/general-ledger"))
                        .param("accountId", CASH_ACCOUNT_ID.toString())
                        .param("startDate", PERIOD_START.toString())
                        .param("endDate", PERIOD_END.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accounts").isEmpty())
                .andExpect(jsonPath("$.totalDebit").value(0))
                .andExpect(jsonPath("$.totalCredit").value(0));
    }

    @Test
    @DisplayName("Aged Receivables reconciles open invoice balances into age buckets")
    void agedReceivablesReconciles() throws Exception {
        saveInvoice("INV-1", "POSTED", new BigDecimal("1000.00"), AS_OF.minusDays(15)); // current
        saveInvoice("INV-2", "FINALIZED", new BigDecimal("500.00"), AS_OF.minusDays(45)); // 31-60

        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/aged-receivables"))
                        .param("asOfDate", AS_OF.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].customerId").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.rows[0].current").value(1000.00))
                .andExpect(jsonPath("$.rows[0].days31To60").value(500.00))
                .andExpect(jsonPath("$.rows[0].totalOutstanding").value(1500.00))
                .andExpect(jsonPath("$.totals.current").value(1000.00))
                .andExpect(jsonPath("$.totals.days31To60").value(500.00))
                .andExpect(jsonPath("$.totals.totalOutstanding").value(1500.00));
    }

    @Test
    @DisplayName("Aged Payables reconciles open vendor-bill balances into age buckets")
    void agedPayablesReconciles() throws Exception {
        saveBill("BILL-1", new BigDecimal("800.00"), AS_OF.minusDays(70)); // 61-90

        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/aged-payables"))
                        .param("asOfDate", AS_OF.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.asOfDate").value("2026-06-30"))
                .andExpect(jsonPath("$.rows.length()").value(1))
                .andExpect(jsonPath("$.rows[0].vendorId").value(VENDOR_ID.toString()))
                .andExpect(jsonPath("$.rows[0].vendorName").value("Global Parts Supply"))
                .andExpect(jsonPath("$.rows[0].days61To90").value(800.00))
                .andExpect(jsonPath("$.rows[0].totalOutstanding").value(800.00))
                .andExpect(jsonPath("$.totals.days61To90").value(800.00))
                .andExpect(jsonPath("$.totals.totalOutstanding").value(800.00));
    }

    @Test
    @DisplayName("Export round-trip: G2 report-type keys flow through request -> status")
    void exportRoundTripForG2ReportTypes() throws Exception {
        for (String reportType : new String[] {"GENERAL_LEDGER", "AGED_RECEIVABLES", "AGED_PAYABLES"}) {
            String body = """
                    {
                      "format": "PDF",
                      "reportType": "%s",
                      "startDate": "2026-06-01",
                      "endDate": "2026-06-30",
                      "organizationId": "d10217f9-3ec6-46b9-9c87-e7066c100c24"
                    }
                    """.formatted(reportType);

            MvcResult created = mockMvc.perform(
                            withAuth(post("/v1/accounting/reports/export"), "accounting:report:export")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body)
                                    .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.exportId").exists())
                    .andExpect(jsonPath("$.status").value("PENDING"))
                    .andExpect(jsonPath("$.reportType").value(reportType))
                    .andReturn();

            String exportId = objectMapper
                    .readTree(created.getResponse().getContentAsString())
                    .get("exportId")
                    .asText();

            mockMvc.perform(withAuth(
                                    get("/v1/accounting/reports/export/{exportId}", exportId),
                                    "accounting:report:export")
                            .accept(MediaType.APPLICATION_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.exportId").value(exportId))
                    .andExpect(jsonPath("$.reportType").value(reportType));
        }
    }

    // ========== Fixtures ==========

    private GLAccount saveAccount(UUID id, String code, String name) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(AccountType.ASSET);
        account.setCreatedBy("TEST");
        account.setModifiedBy("TEST");
        return glAccountRepository.save(account);
    }

    private void savePostedEntry(
            String entryNumber, LocalDateTime transactionDate, GLAccount account, BigDecimal debit, BigDecimal credit) {
        saveEntry(entryNumber, transactionDate, account, debit, credit, JournalEntryStatus.POSTED);
    }

    private void saveEntry(
            String entryNumber,
            LocalDateTime transactionDate,
            GLAccount account,
            BigDecimal debit,
            BigDecimal credit,
            JournalEntryStatus status) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(status);
        entry.setEntryNumber(entryNumber);
        entry.setTransactionDate(transactionDate);
        entry.setSourceEventType("TEST_EVENT");

        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setAccountName(account.getAccountName());
        line.setDebitAmount(debit != null ? debit : BigDecimal.ZERO);
        line.setCreditAmount(credit != null ? credit : BigDecimal.ZERO);
        line.setDescription("test line");
        entry.addLine(line);

        entry.setTotalDebits(line.getDebitAmount());
        entry.setTotalCredits(line.getCreditAmount());
        entry.setIsBalanced(true);

        journalEntryRepository.save(entry);
    }

    private void saveInvoice(String invoiceNumber, String status, BigDecimal total, LocalDate invoiceDate) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber(invoiceNumber)
                .workorderId(UUID.randomUUID())
                .partyId(CUSTOMER_ID.toString())
                .status(status)
                .total(total)
                .invoiceCreatedAt(invoiceDate.atStartOfDay().toInstant(ZoneOffset.UTC))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-30T00:00:00Z"))
                .build();
        extInvoiceRepository.save(invoice);
    }

    private void saveBill(String billNumber, BigDecimal total, LocalDate dueDate) {
        VendorBill bill = new VendorBill(UUID.randomUUID());
        bill.setVendorId(VENDOR_ID);
        bill.setVendorName("Global Parts Supply");
        bill.setBillNumber(billNumber);
        bill.setBillDate(dueDate.atStartOfDay());
        bill.setDueDate(dueDate.atStartOfDay());
        bill.setTotalAmount(total);
        bill.setStatus(VendorBillStatus.APPROVED);
        bill.setCreatedBy("TEST");
        bill.setModifiedBy("TEST");
        vendorBillRepository.save(bill);
    }
}
