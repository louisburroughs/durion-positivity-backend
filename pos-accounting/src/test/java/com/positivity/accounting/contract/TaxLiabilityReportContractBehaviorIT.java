package com.positivity.accounting.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.positivity.accounting.internal.entity.CreditMemo;
import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.entity.ExtInvoiceTax;
import com.positivity.accounting.internal.entity.GLAccount;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.internal.enums.AccountType;
import com.positivity.accounting.internal.enums.CreditMemoStatus;
import com.positivity.accounting.internal.enums.JournalEntryStatus;
import com.positivity.accounting.internal.repository.CreditMemoRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceRepository;
import com.positivity.accounting.internal.repository.ExtInvoiceTaxRepository;
import com.positivity.accounting.internal.repository.GLAccountRepository;
import com.positivity.accounting.internal.repository.JournalEntryRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Contract behavior tests for the Story T8 (issue #966) Sales-Tax Liability
 * endpoint: per-jurisdiction JSON figures with GL-drift reconciliation, plus a
 * CSV/PDF export round-trip proving both formats reference the same report.
 */
@Transactional
class TaxLiabilityReportContractBehaviorIT extends BaseContractIntegrationTest {

    @Autowired
    private GLAccountRepository glAccountRepository;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private ExtInvoiceRepository extInvoiceRepository;

    @Autowired
    private ExtInvoiceTaxRepository extInvoiceTaxRepository;

    @Autowired
    private CreditMemoRepository creditMemoRepository;

    @MockitoBean
    private DocumentRenderClient documentRenderClient;

    private static final UUID AR_ID = UUID.fromString("50000000-0000-7000-8000-000000001200");
    private static final UUID TAX_ID = UUID.fromString("50000000-0000-7000-8000-000000002200");

    private static final LocalDate START = LocalDate.of(2026, 6, 1);
    private static final LocalDate END = LocalDate.of(2026, 6, 30);

    @Test
    @DisplayName("tax-liability returns per-jurisdiction totals with reasons, netted credits, and zero GL drift")
    void taxLiabilityReconciles() throws Exception {
        seedJune2026TaxData();

        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/tax-liability"))
                        .param("startDate", START.toString())
                        .param("endDate", END.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.startDate").value("2026-06-01"))
                .andExpect(jsonPath("$.endDate").value("2026-06-30"))
                .andExpect(jsonPath("$.rows.length()").value(3))
                // State WA first (ordering state -> county -> city).
                .andExpect(jsonPath("$.rows[0].jurisdictionType").value("STATE"))
                .andExpect(jsonPath("$.rows[0].jurisdictionCode").value("WA"))
                .andExpect(jsonPath("$.rows[0].taxableBase").value(3000.00))
                .andExpect(jsonPath("$.rows[0].exemptBase").value(500.00))
                .andExpect(jsonPath("$.rows[0].exemptionReasons[0]").value("RESALE"))
                .andExpect(jsonPath("$.rows[0].taxCollectedGross").value(195.00))
                .andExpect(jsonPath("$.rows[0].creditsNetted").value(13.00))
                .andExpect(jsonPath("$.rows[0].netTax").value(182.00))
                .andExpect(jsonPath("$.rows[1].jurisdictionType").value("COUNTY"))
                .andExpect(jsonPath("$.rows[1].netTax").value(28.00))
                .andExpect(jsonPath("$.rows[2].jurisdictionType").value("CITY"))
                .andExpect(jsonPath("$.rows[2].netTax").value(20.00))
                .andExpect(jsonPath("$.totalTaxCollectedGross").value(255.00))
                .andExpect(jsonPath("$.totalCreditsNetted").value(25.00))
                .andExpect(jsonPath("$.totalNetTax").value(230.00))
                .andExpect(jsonPath("$.reconciliation.taxPayableAccountCode").value("2200"))
                .andExpect(jsonPath("$.reconciliation.glNetActivity").value(230.00))
                .andExpect(jsonPath("$.reconciliation.drift").value(0))
                .andExpect(jsonPath("$.reconciliation.reconciled").value(true));
    }

    @Test
    @DisplayName("Invalid date range returns 400")
    void invalidRange() throws Exception {
        mockMvc.perform(withAuth(get("/v1/accounting/reports/financial/tax-liability"))
                        .param("startDate", END.toString())
                        .param("endDate", START.toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CSV export renders real rows matching the JSON report to the cent, deterministically")
    void exportRendersCsv() throws Exception {
        seedJune2026TaxData();

        String firstCsv = requestExportAndDownload("CSV", "text/csv");
        String[] lines = firstCsv.split("\n");

        assertEquals("Report,Start Date,End Date", lines[0]);
        assertEquals("TAX_LIABILITY,2026-06-01,2026-06-30", lines[1]);
        assertEquals(
                "Jurisdiction Type,Jurisdiction Code,Jurisdiction Name,Taxable Base,Exempt Base,"
                        + "Exemption Reasons,Tax Collected Gross,Credits Netted,Net Tax",
                lines[3]);

        // Figures match the JSON report assertions in taxLiabilityReconciles() to the cent.
        assertCsvRow(lines[4], "STATE", "WA", "3000.00", "500.00", "RESALE", "195.00", "13.00", "182.00");
        String[] county = splitCsv(lines[5]);
        assertEquals("COUNTY", county[0]);
        assertEquals(0, new BigDecimal("28.00").compareTo(new BigDecimal(county[8])));
        String[] city = splitCsv(lines[6]);
        assertEquals("CITY", city[0]);
        assertEquals(0, new BigDecimal("20.00").compareTo(new BigDecimal(city[8])));

        String[] total = splitCsv(lines[7]);
        assertEquals("TOTAL", total[0]);
        assertEquals(0, new BigDecimal("255.00").compareTo(new BigDecimal(total[6])));
        assertEquals(0, new BigDecimal("25.00").compareTo(new BigDecimal(total[7])));
        assertEquals(0, new BigDecimal("230.00").compareTo(new BigDecimal(total[8])));

        assertEquals(
                "Tax Payable Account,GL Net Activity,Report Net Tax,Unattributed Credits,Drift,Reconciled", lines[9]);
        String[] reconciliation = splitCsv(lines[10]);
        assertEquals("2200", reconciliation[0]);
        assertEquals(0, new BigDecimal("230.00").compareTo(new BigDecimal(reconciliation[1])));
        assertEquals(0, BigDecimal.ZERO.compareTo(new BigDecimal(reconciliation[4])));
        assertEquals("true", reconciliation[5]);

        // Determinism: a second export of the same period renders byte-identical CSV.
        assertEquals(firstCsv, requestExportAndDownload("CSV", "text/csv"));
    }

    @Test
    @DisplayName("PDF export renders a non-empty PDF via pos-documents from the deterministic CSV")
    void exportRendersPdf() throws Exception {
        seedJune2026TaxData();
        byte[] fakePdf = "%PDF-1.4 t8-rendered".getBytes(StandardCharsets.UTF_8);
        when(documentRenderClient.renderPdfFromCsv(anyString(), anyString())).thenReturn(fakePdf);

        String exportId = requestExport("PDF")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(exportId).get("exportId").asText();

        byte[] pdf = mockMvc.perform(withAuth(
                        get("/v1/accounting/reports/export/{exportId}/download", id),
                        "reporting:view:financial-statements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andReturn()
                .getResponse()
                .getContentAsByteArray();
        assertTrue(pdf.length > 0);
        assertArrayEquals(fakePdf, pdf);

        // The CSV sent to pos-documents carries the real report figures.
        ArgumentCaptor<String> csvCaptor = ArgumentCaptor.forClass(String.class);
        verify(documentRenderClient).renderPdfFromCsv(anyString(), csvCaptor.capture());
        assertTrue(csvCaptor.getValue().contains("STATE,WA"));
    }

    @Test
    @DisplayName("Download requires reporting:view:financial-statements")
    void downloadForbiddenWithoutViewPermission() throws Exception {
        seedJune2026TaxData();
        String created = requestExport("CSV")
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(created).get("exportId").asText();

        mockMvc.perform(withAuth(
                        get("/v1/accounting/reports/export/{exportId}/download", id), "accounting:report:export"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Unsupported report type fails the export with a reason instead of pretending PENDING")
    void unsupportedReportTypeFails() throws Exception {
        String body = """
                {
                  "format": "CSV",
                  "reportType": "JOURNAL_LINES",
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "organizationId": "d10217f9-3ec6-46b9-9c87-e7066c100c24"
                }
                """;
        mockMvc.perform(withAuth(post("/v1/accounting/reports/export"), "accounting:report:export")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failureReason").exists());
    }

    // ===== export helpers =====

    private org.springframework.test.web.servlet.ResultActions requestExport(String format) throws Exception {
        String body = """
                {
                  "format": "%s",
                  "reportType": "TAX_LIABILITY",
                  "startDate": "2026-06-01",
                  "endDate": "2026-06-30",
                  "organizationId": "d10217f9-3ec6-46b9-9c87-e7066c100c24"
                }
                """.formatted(format);
        return mockMvc.perform(withAuth(post("/v1/accounting/reports/export"), "accounting:report:export")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .accept(MediaType.APPLICATION_JSON));
    }

    private String requestExportAndDownload(String format, String expectedContentType) throws Exception {
        String created = requestExport(format)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exportId").exists())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.format").value(format))
                .andExpect(jsonPath("$.reportType").value("TAX_LIABILITY"))
                .andExpect(jsonPath("$.downloadUrl").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = objectMapper.readTree(created).get("exportId").asText();

        mockMvc.perform(withAuth(get("/v1/accounting/reports/export/{exportId}", id), "accounting:report:export")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.exportId").value(id))
                .andExpect(jsonPath("$.status").value("COMPLETED"));

        return mockMvc.perform(withAuth(
                        get("/v1/accounting/reports/export/{exportId}/download", id),
                        "reporting:view:financial-statements"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", expectedContentType))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void assertCsvRow(
            String line,
            String type,
            String code,
            String taxableBase,
            String exemptBase,
            String reasons,
            String gross,
            String credits,
            String net) {
        String[] parts = splitCsv(line);
        assertEquals(type, parts[0]);
        assertEquals(code, parts[1]);
        assertEquals(0, new BigDecimal(taxableBase).compareTo(new BigDecimal(parts[3])));
        assertEquals(0, new BigDecimal(exemptBase).compareTo(new BigDecimal(parts[4])));
        assertEquals(reasons, parts[5]);
        assertEquals(0, new BigDecimal(gross).compareTo(new BigDecimal(parts[6])));
        assertEquals(0, new BigDecimal(credits).compareTo(new BigDecimal(parts[7])));
        assertEquals(0, new BigDecimal(net).compareTo(new BigDecimal(parts[8])));
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

    private void seedJune2026TaxData() {
        GLAccount ar = saveAccount(AR_ID, "1200", "Accounts Receivable", AccountType.ASSET);
        GLAccount tax = saveAccount(TAX_ID, "2200", "Sales Tax Payable", AccountType.LIABILITY);

        UUID inv1 = UUID.randomUUID();
        UUID inv2 = UUID.randomUUID();
        saveInvoice(inv1);
        saveInvoice(inv2);
        saveTax(inv1, "STATE", "WA", "1000.00", "65.00", false, null);
        saveTax(inv1, "COUNTY", "KING", "1000.00", "35.00", false, null);
        saveTax(inv1, "CITY", "SEATTLE", "1000.00", "25.00", false, null);
        saveTax(inv1, "STATE", "WA", "500.00", "0.00", true, "RESALE");
        saveTax(inv2, "STATE", "WA", "2000.00", "130.00", false, null);
        saveCredit(inv1, "25.00");

        // Balanced GL so the 2200 credit-normal activity (230) matches report net tax.
        postBalancedEntry("JE-T8C-INV", LocalDateTime.of(2026, 6, 15, 0, 0), ar, tax, new BigDecimal("255.00"));
        postBalancedEntry("JE-T8C-CRD", LocalDateTime.of(2026, 6, 20, 0, 0), tax, ar, new BigDecimal("25.00"));
    }

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

    private void saveInvoice(UUID invoiceId) {
        ExtInvoice invoice = ExtInvoice.builder()
                .invoiceId(invoiceId)
                .workorderId(UUID.randomUUID())
                .partyId(UUID.randomUUID().toString())
                .status("FINALIZED")
                .finalizedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .invoiceCreatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-30T00:00:00Z"))
                .build();
        extInvoiceRepository.save(invoice);
    }

    private void saveTax(
            UUID invoiceId, String type, String code, String base, String tax, boolean exempt, String reason) {
        ExtInvoiceTax row = ExtInvoiceTax.builder()
                .invoiceId(invoiceId)
                .lineItemId(code + "-line")
                .jurisdictionType(type)
                .jurisdictionCode(code)
                .rate(new BigDecimal("0.065000"))
                .taxableBase(new BigDecimal(base))
                .taxAmount(new BigDecimal(tax))
                .exempt(exempt)
                .exemptionReasonCode(reason)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-06-15T00:00:00Z"))
                .build();
        extInvoiceTaxRepository.save(row);
    }

    private void saveCredit(UUID originalInvoiceId, String taxReversed) {
        CreditMemo credit = new CreditMemo();
        credit.setOriginalInvoiceId(originalInvoiceId);
        credit.setCustomerId(UUID.randomUUID());
        credit.setCreditAmount(new BigDecimal("100.00"));
        credit.setTaxAmountReversed(new BigDecimal(taxReversed));
        credit.setReasonCode("RETURN");
        credit.setStatus(CreditMemoStatus.POSTED);
        credit.setCreationTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setPostedTimestamp(Instant.parse("2026-06-20T00:00:00Z"));
        credit.setCreatedByUserId("t8-it");
        credit.setCurrency("USD");
        creditMemoRepository.save(credit);
    }

    private void postBalancedEntry(
            String entryNumber,
            LocalDateTime txDate,
            GLAccount debitAccount,
            GLAccount creditAccount,
            BigDecimal amount) {
        JournalEntry entry = new JournalEntry();
        entry.setStatus(JournalEntryStatus.POSTED);
        entry.setEntryNumber(entryNumber);
        entry.setTransactionDate(txDate);
        entry.setSourceEventType("T8_TEST");
        entry.setCreatedBy("t8-it");
        entry.setModifiedBy("t8-it");
        entry.setTotalDebits(amount);
        entry.setTotalCredits(amount);
        entry.setIsBalanced(true);
        entry.addLine(line(entry, 1, debitAccount, amount, BigDecimal.ZERO));
        entry.addLine(line(entry, 2, creditAccount, BigDecimal.ZERO, amount));
        journalEntryRepository.save(entry);
    }

    private static JournalEntryLine line(
            JournalEntry entry, int lineNumber, GLAccount account, BigDecimal debit, BigDecimal credit) {
        JournalEntryLine line = new JournalEntryLine();
        line.setJournalEntry(entry);
        line.setLineNumber(lineNumber);
        line.setGlAccount(account);
        line.setAccountCode(account.getAccountCode());
        line.setAccountName(account.getAccountName());
        line.setDebitAmount(debit);
        line.setCreditAmount(credit);
        line.setDescription("t8 test line");
        return line;
    }
}
