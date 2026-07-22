package com.positivity.accounting.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.accounting.BaseContractIntegrationTest;
import com.positivity.accounting.internal.client.DocumentRenderClient;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.entity.StatementLineMapping;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.enums.OperationType;
import com.positivity.accounting.internal.enums.StatementType;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import com.positivity.accounting.internal.repository.StatementLineMappingRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import tools.jackson.databind.JsonNode;

/**
 * Contract behavior tests for the report-export rendering rails wired in issues
 * #1011-#1015: INCOME_STATEMENT, BALANCE_SHEET, TRIAL_BALANCE, GENERAL_LEDGER,
 * and AGED_RECEIVABLES / AGED_PAYABLES.
 *
 * <p>Each test proves the rendered CSV figures match the corresponding JSON
 * report endpoint to the cent (not just export metadata), that re-rendering the
 * same data is byte-identical, that the as-of reports use the export request's
 * {@code endDate} as the as-of date, and that PDF rendering flows the
 * deterministic CSV through pos-documents with clean FAILED handling when the
 * render is rejected.
 */
@Transactional
class ReportExportRenderingContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private StatementLineMappingRepository statementLineMappingRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private VendorBillRepository vendorBillRepository;

    @MockitoBean
    private DocumentRenderClient documentRenderClient;

    private static final UUID CASH_ID = UUID.fromString("60000000-0000-7000-8000-000000001000");
    private static final UUID AP_ID = UUID.fromString("60000000-0000-7000-8000-000000002000");
    private static final UUID EQUITY_ID = UUID.fromString("60000000-0000-7000-8000-000000003000");
    private static final UUID REVENUE_ID = UUID.fromString("60000000-0000-7000-8000-000000004000");
    private static final UUID COGS_ID = UUID.fromString("60000000-0000-7000-8000-000000005000");
    private static final UUID CUSTOMER_ID = UUID.fromString("60000000-0000-7000-8000-000000000101");
    private static final UUID VENDOR_ID = UUID.fromString("60000000-0000-7000-8000-000000000102");

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    private GLAccount cash;
    private GLAccount ap;
    private GLAccount revenue;
    private GLAccount cogs;

    @BeforeEach
    void seedFixtures() {
        cash = saveAccount(CASH_ID, "1000", "Cash - Operating", AccountType.ASSET);
        ap = saveAccount(AP_ID, "2000", "Accounts Payable", AccountType.LIABILITY);
        saveAccount(EQUITY_ID, "3000", "Owner's Equity", AccountType.EQUITY);
        revenue = saveAccount(REVENUE_ID, "4000", "Sales Revenue", AccountType.REVENUE);
        cogs = saveAccount(COGS_ID, "5000", "Cost of Goods Sold", AccountType.EXPENSE);

        saveMapping(StatementType.INCOME_STATEMENT, "REVENUE_SALES", REVENUE_ID, "Sales Revenue", 10);
        saveMapping(StatementType.INCOME_STATEMENT, "EXPENSE_COGS", COGS_ID, "Cost of Goods Sold", 20);
        saveMapping(StatementType.BALANCE_SHEET, "ASSET_CURRENT_CASH", CASH_ID, "Cash", 10);
        saveMapping(StatementType.BALANCE_SHEET, "LIABILITY_CURRENT_AP", AP_ID, "Accounts Payable", 50);
        saveMapping(StatementType.BALANCE_SHEET, "EQUITY_OWNERS", EQUITY_ID, "Owner's Equity", 80);

        // Balanced POSTED entries: cash 1000 D / revenue 1000 C, cogs 400 D / AP 400 C.
        savePostedEntry(
                "JE-202606-1",
                LocalDateTime.of(2026, 6, 5, 0, 0),
                cash,
                new BigDecimal("1000.00"),
                revenue,
                new BigDecimal("1000.00"));
        savePostedEntry(
                "JE-202606-2",
                LocalDateTime.of(2026, 6, 20, 0, 0),
                cogs,
                new BigDecimal("400.00"),
                ap,
                new BigDecimal("400.00"));

        saveInvoice("INV-1", new BigDecimal("1000.00"), END.minusDays(15)); // current
        saveInvoice("INV-2", new BigDecimal("500.00"), END.minusDays(45)); // 31-60
        saveBill("BILL-1", new BigDecimal("800.00"), END.minusDays(70)); // 61-90
    }

    @Test
    @DisplayName("INCOME_STATEMENT CSV matches the JSON report to the cent and re-renders byte-identical")
    void incomeStatementCsvMatchesJson() throws Exception {
        JsonNode json = fetchJson(get("/v1/accounting/reports/financial/income-statement")
                .param("startDate", START.toString())
                .param("endDate", END.toString()));

        String csv = exportCsv("INCOME_STATEMENT");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,Start Date,End Date", lines[0]);
        assertEquals("INCOME_STATEMENT,2026-06-01,2026-06-30", lines[1]);
        assertEquals("Line Item,Amount", lines[3]);

        int row = 4;
        for (Map.Entry<String, JsonNode> field : json.get("lineItems").properties()) {
            String[] parts = splitCsv(lines[row++]);
            assertEquals(field.getKey(), parts[0]);
            assertMoneyEquals(field.getValue(), parts[1]);
        }
        assertMoneyRow(lines[row++], "TOTAL_REVENUE", json.get("totalRevenue"));
        assertMoneyRow(lines[row++], "TOTAL_EXPENSES", json.get("totalExpenses"));
        assertMoneyRow(lines[row], "NET_INCOME", json.get("netIncome"));

        assertEquals(csv, exportCsv("INCOME_STATEMENT"));
    }

    @Test
    @DisplayName("BALANCE_SHEET CSV uses endDate as the as-of date and matches the JSON report to the cent")
    void balanceSheetCsvMatchesJson() throws Exception {
        JsonNode json =
                fetchJson(get("/v1/accounting/reports/financial/balance-sheet").param("asOfDate", END.toString()));

        String csv = exportCsv("BALANCE_SHEET");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,As-Of Date", lines[0]);
        // The export request's endDate is the as-of date (issue #1012 decision).
        assertEquals("BALANCE_SHEET," + END, lines[1]);
        assertEquals("Line Item,Amount", lines[3]);

        int row = 4;
        for (Map.Entry<String, JsonNode> field : json.get("lineItems").properties()) {
            String[] parts = splitCsv(lines[row++]);
            assertEquals(field.getKey(), parts[0]);
            assertMoneyEquals(field.getValue(), parts[1]);
        }
        assertMoneyRow(lines[row++], "TOTAL_ASSETS", json.get("totalAssets"));
        assertMoneyRow(lines[row++], "TOTAL_LIABILITIES", json.get("totalLiabilities"));
        assertMoneyRow(lines[row++], "TOTAL_EQUITY", json.get("totalEquity"));
        assertEquals("BALANCED," + json.get("balanced").asBoolean(), lines[row]);

        assertEquals(csv, exportCsv("BALANCE_SHEET"));
    }

    @Test
    @DisplayName("TRIAL_BALANCE CSV matches per-account debits/credits and the balancing totals row to the cent")
    void trialBalanceCsvMatchesJson() throws Exception {
        JsonNode json =
                fetchJson(get("/v1/accounting/reports/financial/trial-balance").param("asOf", END.toString()));

        String csv = exportCsv("TRIAL_BALANCE");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,As-Of Date", lines[0]);
        assertEquals("TRIAL_BALANCE," + END, lines[1]);
        assertEquals("Account Number,Account Name,Total Debit,Total Credit,Balance", lines[3]);

        JsonNode rows = json.get("rows");
        assertFalse(rows.isEmpty(), "expected seeded accounts in the trial balance");
        int row = 4;
        for (JsonNode jsonRow : rows) {
            String[] parts = splitCsv(lines[row++]);
            assertEquals(jsonRow.get("accountNumber").asText(), parts[0]);
            assertEquals(jsonRow.get("accountName").asText(), parts[1]);
            assertMoneyEquals(jsonRow.get("totalDebit"), parts[2]);
            assertMoneyEquals(jsonRow.get("totalCredit"), parts[3]);
            assertMoneyEquals(jsonRow.get("balance"), parts[4]);
        }
        String[] total = splitCsv(lines[row++]);
        assertEquals("TOTAL", total[0]);
        assertMoneyEquals(json.get("totalDebit"), total[2]);
        assertMoneyEquals(json.get("totalCredit"), total[3]);
        assertEquals("BALANCED," + json.get("balanced").asBoolean(), lines[row]);
        assertTrue(csv.contains("Sequence Scope,Missing Entry Numbers"));

        assertEquals(csv, exportCsv("TRIAL_BALANCE"));
    }

    @Test
    @DisplayName("GENERAL_LEDGER CSV matches sections, running balances, and grand totals to the cent")
    void generalLedgerCsvMatchesJson() throws Exception {
        JsonNode json = fetchJson(get("/v1/accounting/reports/financial/general-ledger")
                .param("startDate", START.toString())
                .param("endDate", END.toString()));

        String csv = exportCsv("GENERAL_LEDGER");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,Start Date,End Date,Account Filter", lines[0]);
        assertEquals("GENERAL_LEDGER,2026-06-01,2026-06-30,ALL", lines[1]);
        assertEquals(
                "Account Number,Account Name,Entry Number,Transaction Date,Description,Debit,Credit,Running Balance",
                lines[3]);

        int row = 4;
        for (JsonNode section : json.get("accounts")) {
            String number = section.get("accountNumber").asText();
            String[] opening = splitCsv(lines[row++]);
            assertEquals(number, opening[0]);
            assertEquals("OPENING BALANCE", opening[2]);
            assertMoneyEquals(section.get("openingBalance"), opening[7]);
            for (JsonNode line : section.get("lines")) {
                String[] parts = splitCsv(lines[row++]);
                assertEquals(number, parts[0]);
                assertEquals(line.get("entryNumber").asText(), parts[2]);
                assertEquals(line.get("transactionDate").asText(), parts[3]);
                assertMoneyEquals(line.get("runningBalance"), parts[7]);
            }
            String[] sectionTotal = splitCsv(lines[row++]);
            assertEquals("ACCOUNT TOTAL", sectionTotal[2]);
            assertMoneyEquals(section.get("totalDebit"), sectionTotal[5]);
            assertMoneyEquals(section.get("totalCredit"), sectionTotal[6]);
            assertMoneyEquals(section.get("closingBalance"), sectionTotal[7]);
        }
        String[] grand = splitCsv(lines[row]);
        assertEquals("GRAND TOTAL", grand[0]);
        assertMoneyEquals(json.get("totalDebit"), grand[5]);
        assertMoneyEquals(json.get("totalCredit"), grand[6]);

        assertEquals(csv, exportCsv("GENERAL_LEDGER"));
    }

    @Test
    @DisplayName("GENERAL_LEDGER honors the optional accountId filter")
    void generalLedgerAccountFilter() throws Exception {
        String csv = exportCsv("GENERAL_LEDGER", "\"accountId\": \"" + CASH_ID + "\",");
        String[] lines = csv.split("\n", -1);

        assertEquals("GENERAL_LEDGER,2026-06-01,2026-06-30," + CASH_ID, lines[1]);
        assertTrue(csv.contains("1000,Cash - Operating,OPENING BALANCE"));
        assertFalse(csv.contains("4000,Sales Revenue"), "filtered export must not contain other accounts");
    }

    @Test
    @DisplayName("AGED_RECEIVABLES CSV matches bucket figures to the cent using endDate as the as-of date")
    void agedReceivablesCsvMatchesJson() throws Exception {
        JsonNode json = fetchJson(
                get("/v1/accounting/reports/financial/aged-receivables").param("asOfDate", END.toString()));

        String csv = exportCsv("AGED_RECEIVABLES");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,As-Of Date", lines[0]);
        assertEquals("AGED_RECEIVABLES," + END, lines[1]);
        assertEquals(
                "Customer ID,Customer Name,Current (0-30),31-60 Days,61-90 Days,90+ Days,Total Outstanding", lines[3]);

        JsonNode rows = json.get("rows");
        assertFalse(rows.isEmpty(), "expected seeded open receivables");
        int row = 4;
        for (JsonNode jsonRow : rows) {
            String[] parts = splitCsv(lines[row++]);
            assertEquals(jsonRow.get("customerId").asText(), parts[0]);
            assertMoneyEquals(jsonRow.get("current"), parts[2]);
            assertMoneyEquals(jsonRow.get("days31To60"), parts[3]);
            assertMoneyEquals(jsonRow.get("days61To90"), parts[4]);
            assertMoneyEquals(jsonRow.get("days90Plus"), parts[5]);
            assertMoneyEquals(jsonRow.get("totalOutstanding"), parts[6]);
        }
        String[] total = splitCsv(lines[row]);
        assertEquals("TOTAL", total[0]);
        assertMoneyEquals(json.get("totals").get("current"), total[2]);
        assertMoneyEquals(json.get("totals").get("days31To60"), total[3]);
        assertMoneyEquals(json.get("totals").get("totalOutstanding"), total[6]);

        assertEquals(csv, exportCsv("AGED_RECEIVABLES"));
    }

    @Test
    @DisplayName("AGED_PAYABLES CSV matches bucket figures to the cent using endDate as the as-of date")
    void agedPayablesCsvMatchesJson() throws Exception {
        JsonNode json =
                fetchJson(get("/v1/accounting/reports/financial/aged-payables").param("asOfDate", END.toString()));

        String csv = exportCsv("AGED_PAYABLES");
        String[] lines = csv.split("\n", -1);

        assertEquals("Report,As-Of Date", lines[0]);
        assertEquals("AGED_PAYABLES," + END, lines[1]);
        assertEquals("Vendor ID,Vendor Name,Current (0-30),31-60 Days,61-90 Days,90+ Days,Total Outstanding", lines[3]);

        JsonNode rows = json.get("rows");
        assertFalse(rows.isEmpty(), "expected seeded open payables");
        int row = 4;
        for (JsonNode jsonRow : rows) {
            String[] parts = splitCsv(lines[row++]);
            assertEquals(jsonRow.get("vendorId").asText(), parts[0]);
            assertMoneyEquals(jsonRow.get("current"), parts[2]);
            assertMoneyEquals(jsonRow.get("days61To90"), parts[4]);
            assertMoneyEquals(jsonRow.get("totalOutstanding"), parts[6]);
        }
        String[] total = splitCsv(lines[row]);
        assertEquals("TOTAL", total[0]);
        assertMoneyEquals(json.get("totals").get("days61To90"), total[4]);
        assertMoneyEquals(json.get("totals").get("totalOutstanding"), total[6]);

        assertEquals(csv, exportCsv("AGED_PAYABLES"));
    }

    @Test
    @DisplayName("PDF export flows the deterministic CSV through pos-documents")
    void pdfExportUsesRenderedCsv() throws Exception {
        byte[] fakePdf = "%PDF-1.4 income-statement".getBytes(StandardCharsets.UTF_8);
        when(documentRenderClient.renderPdfFromCsv(anyString(), anyString())).thenReturn(fakePdf);

        String created = requestExport("INCOME_STATEMENT", "PDF", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(created).get("exportId").asText();

        byte[] pdf = mockMvc.perform(withAuth(
                        get("/v1/accounting/reports/export/{exportId}/download", id),
                        "reporting:view:financial-statements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertArrayEquals(fakePdf, pdf);

        ArgumentCaptor<String> csvCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRenderClient).renderPdfFromCsv(anyString(), csvCaptor.capture());
        assertTrue(csvCaptor.getValue().startsWith("Report,Start Date,End Date\nINCOME_STATEMENT,"));
    }

    @Test
    @DisplayName("PDF render rejected by pos-documents fails cleanly with a failureReason, not a 500")
    void oversizedPdfFailsCleanly() throws Exception {
        when(documentRenderClient.renderPdfFromCsv(anyString(), anyString()))
                .thenThrow(HttpClientErrorException.create(
                        HttpStatus.PAYLOAD_TOO_LARGE, "Payload Too Large", HttpHeaders.EMPTY, new byte[0], null));

        requestExport("GENERAL_LEDGER", "PDF", "")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason")
                        .value(org.hamcrest.Matchers.containsString("pos-documents returned 413")));
    }

    // ===== export helpers =====

    private org.springframework.test.web.servlet.ResultActions requestExport(
            String reportType, String format, String extraFields) throws Exception {
        String body = """
                {
                  "format": "%s",
                  "reportType": "%s",
                  %s
                  "startDate": "%s",
                  "endDate": "%s",
                  "organizationId": "d10217f9-3ec6-46b9-9c87-e7066c100c24"
                }
                """.formatted(format, reportType, extraFields, START, END);
        return mockMvc.perform(withAuth(post("/v1/accounting/reports/export"), "accounting:report:export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON));
    }

    private String exportCsv(String reportType) throws Exception {
        return exportCsv(reportType, "");
    }

    private String exportCsv(String reportType, String extraFields) throws Exception {
        String created = requestExport(reportType, "CSV", extraFields)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reportType").value(reportType))
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(created).get("exportId").asText();

        return mockMvc.perform(withAuth(
                        get("/v1/accounting/reports/export/{exportId}/download", id),
                        "reporting:view:financial-statements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private JsonNode fetchJson(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        String json = mockMvc.perform(withAuth(request).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return objectMapper.readTree(json);
    }

    private static void assertMoneyRow(String line, String label, JsonNode expected) {
        String[] parts = splitCsv(line);
        assertEquals(label, parts[0]);
        assertMoneyEquals(expected, parts[1]);
    }

    private static void assertMoneyEquals(JsonNode expected, String actual) {
        assertEquals(
                0,
                expected.decimalValue().compareTo(new BigDecimal(actual)),
                "expected " + expected + " but CSV holds " + actual);
    }

    /**
     * CSV-aware single-line field split: honors RFC-4180 quoting so fields containing
     * commas or escaped quotes are not broken apart (unlike {@code String.split(",")}).
     */
    private static String[] splitCsv(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        fields.add(field.toString());
        return fields.toArray(new String[0]);
    }

    // ===== fixtures =====

    private GLAccount saveAccount(UUID id, String code, String name, AccountType type) {
        GLAccount account = new GLAccount();
        account.setGlAccountId(id);
        account.setAccountCode(code);
        account.setAccountName(name);
        account.setAccountType(type);
        account.setCreatedBy("TEST");
        account.setModifiedBy("TEST");
        return glAccountRepository.save(account);
    }

    private void saveMapping(
            StatementType statementType, String lineCode, UUID accountId, String accountName, int displayOrder) {
        statementLineMappingRepository.save(StatementLineMapping.builder()
                .mappingId(UUID.randomUUID())
                .glAccount(new GLAccount(accountId))
                .accountName(accountName)
                .statementType(statementType)
                .statementLineCode(lineCode)
                .lineDescription(lineCode)
                .displayOrder(displayOrder)
                .operation(OperationType.SUM)
                .build());
    }

    private void savePostedEntry(
            String entryNumber,
            LocalDateTime transactionDate,
            GLAccount debitAccount,
            BigDecimal debitAmount,
            GLAccount creditAccount,
            BigDecimal creditAmount) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setEntryNumber(entryNumber);
        entry.setTransactionDate(transactionDate);
        entry.setSourceEventType("TEST_EVENT");

        JournalEntryLine debit = new JournalEntryLine();
        debit.setGlAccount(debitAccount);
        debit.setAccountCode(debitAccount.getAccountCode());
        debit.setAccountName(debitAccount.getAccountName());
        debit.setDebitAmount(debitAmount);
        debit.setCreditAmount(BigDecimal.ZERO);
        debit.setDescription("test debit");
        entry.addLine(debit);

        JournalEntryLine credit = new JournalEntryLine();
        credit.setGlAccount(creditAccount);
        credit.setAccountCode(creditAccount.getAccountCode());
        credit.setAccountName(creditAccount.getAccountName());
        credit.setDebitAmount(BigDecimal.ZERO);
        credit.setCreditAmount(creditAmount);
        credit.setDescription("test credit");
        entry.addLine(credit);

        entry.setTotalDebits(debitAmount);
        entry.setTotalCredits(creditAmount);
        entry.setIsBalanced(true);

        journalEntryRepository.save(entry);
    }

    private void saveInvoice(String invoiceNumber, BigDecimal total, LocalDate invoiceDate) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(UUID.randomUUID())
                .invoiceNumber(invoiceNumber)
                .workorderId(UUID.randomUUID())
                .partyId(CUSTOMER_ID.toString())
                .status("POSTED")
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
