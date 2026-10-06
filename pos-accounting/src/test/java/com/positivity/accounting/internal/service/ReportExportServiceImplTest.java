package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.client.DocumentRenderClient;
import com.positivity.accounting.internal.dto.AgedPayablesReport;
import com.positivity.accounting.internal.dto.AgedReceivablesReport;
import com.positivity.accounting.internal.dto.BalanceSheetReport;
import com.positivity.accounting.internal.dto.GeneralLedgerReport;
import com.positivity.accounting.internal.dto.IncomeStatementReport;
import com.positivity.accounting.internal.dto.ReportExportArtifact;
import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.dto.TrialBalanceReport;
import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReportExportServiceImpl Unit Tests")
class ReportExportServiceImplTest {

    private static final Instant T0 = Instant.parse("2026-04-25T10:00:00Z");
    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 3, 31);
    private static final String OPERATOR = "operator-1";
    private static final String CSV = "Account,Amount\n\"Café, \"\"Main\"\"\",1234.50\n";
    private static final byte[] PDF_BYTES = {'%', 'P', 'D', 'F', '-', '1', '.', '7'};

    @Mock
    private FinancialReportingService financialReportingService;

    @Mock
    private TaxLiabilityCsvRenderer taxLiabilityCsvRenderer;

    @Mock
    private IncomeStatementCsvRenderer incomeStatementCsvRenderer;

    @Mock
    private BalanceSheetCsvRenderer balanceSheetCsvRenderer;

    @Mock
    private TrialBalanceCsvRenderer trialBalanceCsvRenderer;

    @Mock
    private GeneralLedgerCsvRenderer generalLedgerCsvRenderer;

    @Mock
    private AgedReportCsvRenderer agedReportCsvRenderer;

    @Mock
    private DocumentRenderClient documentRenderClient;

    private MutableClock clock;
    private ReportExportServiceImpl service;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
        service = new ReportExportServiceImpl(
                clock,
                financialReportingService,
                taxLiabilityCsvRenderer,
                incomeStatementCsvRenderer,
                balanceSheetCsvRenderer,
                trialBalanceCsvRenderer,
                generalLedgerCsvRenderer,
                agedReportCsvRenderer,
                documentRenderClient);
    }

    private static ReportExportRequest request(String reportType, ExportFormat format) {
        return ReportExportRequest.builder()
                .reportType(reportType)
                .format(format)
                .startDate(START)
                .endDate(END)
                .build();
    }

    private void stubTaxLiabilityCsv(String csv) {
        TaxLiabilityReport report = new TaxLiabilityReport();
        when(financialReportingService.generateTaxLiability(START, END)).thenReturn(report);
        when(taxLiabilityCsvRenderer.render(report)).thenReturn(csv);
    }

    private ReportExportResponse exportTaxLiabilityCsv() {
        return service.requestExport(request("TAX_LIABILITY", ExportFormat.CSV), OPERATOR);
    }

    @Nested
    @DisplayName("requestExport - CSV rendering per report type")
    class CsvRendering {

        @Test
        @DisplayName("TAX_LIABILITY renders the period report to a UTF-8 CSV artifact with a period filename")
        void taxLiability_rendersPeriodCsv() {
            stubTaxLiabilityCsv(CSV);

            ReportExportResponse response = exportTaxLiabilityCsv();

            assertThat(response.getStatus()).isEqualTo(ExportStatus.COMPLETED);
            assertThat(response.getExportId()).isNotNull();
            assertThat(response.getRequestedAt()).isEqualTo(T0);
            assertThat(response.getCompletedAt()).isEqualTo(T0);
            assertThat(response.getFormat()).isEqualTo(ExportFormat.CSV);
            assertThat(response.getReportType()).isEqualTo("TAX_LIABILITY");
            assertThat(response.getFailureReason()).isNull();
            assertThat(response.getDownloadUrl())
                    .isEqualTo("/v1/accounting/reports/export/" + response.getExportId() + "/download");

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.contentType()).isEqualTo("text/csv");
            assertThat(artifact.filename()).isEqualTo("tax-liability-2026-01-01-2026-03-31.csv");
            // CSV escaping from the renderer and non-ASCII characters survive byte-for-byte as UTF-8.
            assertThat(artifact.content()).isEqualTo(CSV.getBytes(StandardCharsets.UTF_8));
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8))
                    .startsWith("Account,Amount\n")
                    .contains("\"Café, \"\"Main\"\"\",1234.50");
            verifyNoInteractions(documentRenderClient);
        }

        @Test
        @DisplayName("INCOME_STATEMENT renders the start..end period report")
        void incomeStatement_rendersPeriodCsv() {
            IncomeStatementReport report = new IncomeStatementReport();
            when(financialReportingService.generateIncomeStatement(START, END)).thenReturn(report);
            when(incomeStatementCsvRenderer.render(report)).thenReturn("Revenue,100.00\n");

            ReportExportResponse response =
                    service.requestExport(request("INCOME_STATEMENT", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(response.getStatus()).isEqualTo(ExportStatus.COMPLETED);
            assertThat(artifact.filename()).isEqualTo("income-statement-2026-01-01-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("Revenue,100.00\n");
        }

        @Test
        @DisplayName("BALANCE_SHEET uses endDate as the as-of date for rendering and filename")
        void balanceSheet_usesEndDateAsOf() {
            BalanceSheetReport report = new BalanceSheetReport();
            when(financialReportingService.generateBalanceSheet(END)).thenReturn(report);
            when(balanceSheetCsvRenderer.render(report)).thenReturn("Assets,10.00\n");

            ReportExportResponse response = service.requestExport(request("BALANCE_SHEET", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.filename()).isEqualTo("balance-sheet-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("Assets,10.00\n");
        }

        @Test
        @DisplayName("TRIAL_BALANCE uses endDate as the as-of date for rendering and filename")
        void trialBalance_usesEndDateAsOf() {
            TrialBalanceReport report = new TrialBalanceReport();
            when(financialReportingService.generateTrialBalance(END)).thenReturn(report);
            when(trialBalanceCsvRenderer.render(report)).thenReturn("Debit,Credit\n");

            ReportExportResponse response = service.requestExport(request("TRIAL_BALANCE", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.filename()).isEqualTo("trial-balance-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("Debit,Credit\n");
        }

        @Test
        @DisplayName("GENERAL_LEDGER without accountId passes a null account filter (all accounts)")
        void generalLedger_withoutAccountId_passesNullFilter() {
            GeneralLedgerReport report = new GeneralLedgerReport();
            when(financialReportingService.generateGeneralLedger(isNull(), eq(START), eq(END)))
                    .thenReturn(report);
            when(generalLedgerCsvRenderer.render(report)).thenReturn("GL\n");

            ReportExportResponse response =
                    service.requestExport(request("GENERAL_LEDGER", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.filename()).isEqualTo("general-ledger-2026-01-01-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("GL\n");
        }

        @Test
        @DisplayName("GENERAL_LEDGER with accountId passes the account UUID as the filter")
        void generalLedger_withAccountId_passesAccountFilter() {
            UUID accountId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
            GeneralLedgerReport report = new GeneralLedgerReport();
            when(financialReportingService.generateGeneralLedger(accountId.toString(), START, END))
                    .thenReturn(report);
            when(generalLedgerCsvRenderer.render(report)).thenReturn("GL-1\n");
            ReportExportRequest request = request("GENERAL_LEDGER", ExportFormat.CSV);
            request.setAccountId(accountId);

            ReportExportResponse response = service.requestExport(request, OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.COMPLETED);
            assertThat(new String(service.downloadExport(response.getExportId()).content(), StandardCharsets.UTF_8))
                    .isEqualTo("GL-1\n");
        }

        @Test
        @DisplayName("AGED_RECEIVABLES renders the as-of receivables report")
        void agedReceivables_usesEndDateAsOf() {
            AgedReceivablesReport report = new AgedReceivablesReport();
            when(financialReportingService.generateAgedReceivables(END)).thenReturn(report);
            when(agedReportCsvRenderer.render(report)).thenReturn("AR\n");

            ReportExportResponse response =
                    service.requestExport(request("AGED_RECEIVABLES", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.filename()).isEqualTo("aged-receivables-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("AR\n");
        }

        @Test
        @DisplayName("AGED_PAYABLES renders the as-of payables report")
        void agedPayables_usesEndDateAsOf() {
            AgedPayablesReport report = new AgedPayablesReport();
            when(financialReportingService.generateAgedPayables(END)).thenReturn(report);
            when(agedReportCsvRenderer.render(report)).thenReturn("AP\n");

            ReportExportResponse response = service.requestExport(request("AGED_PAYABLES", ExportFormat.CSV), OPERATOR);

            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.filename()).isEqualTo("aged-payables-2026-03-31.csv");
            assertThat(new String(artifact.content(), StandardCharsets.UTF_8)).isEqualTo("AP\n");
        }

        @Test
        @DisplayName("an empty report renders to an empty (zero-byte) CSV artifact that still completes")
        void emptyReport_completesWithEmptyArtifact() {
            stubTaxLiabilityCsv("");

            ReportExportResponse response = exportTaxLiabilityCsv();

            assertThat(response.getStatus()).isEqualTo(ExportStatus.COMPLETED);
            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.content()).isEmpty();
            assertThat(artifact.contentType()).isEqualTo("text/csv");
        }
    }

    @Nested
    @DisplayName("requestExport - filenames")
    class Filenames {

        @Test
        @DisplayName("a caller-supplied filename replaces the default base name")
        void customFilename_isUsed() {
            stubTaxLiabilityCsv(CSV);
            ReportExportRequest request = request("TAX_LIABILITY", ExportFormat.CSV);
            request.setFilename("q1-2026_tax.v2");

            ReportExportResponse response = service.requestExport(request, OPERATOR);

            assertThat(service.downloadExport(response.getExportId()).filename())
                    .isEqualTo("q1-2026_tax.v2.csv");
        }

        @Test
        @DisplayName("unsafe characters (CR/LF, quotes, path separators, spaces) are replaced with underscores")
        void customFilename_isSanitized() {
            stubTaxLiabilityCsv(CSV);
            ReportExportRequest request = request("TAX_LIABILITY", ExportFormat.CSV);
            request.setFilename("../evil\"name\r\nX: y");

            ReportExportResponse response = service.requestExport(request, OPERATOR);

            assertThat(service.downloadExport(response.getExportId()).filename())
                    .isEqualTo(".._evil_name__X__y.csv");
        }

        @Test
        @DisplayName("a blank caller-supplied filename falls back to the default base name")
        void blankFilename_fallsBackToDefault() {
            stubTaxLiabilityCsv(CSV);
            ReportExportRequest request = request("TAX_LIABILITY", ExportFormat.CSV);
            request.setFilename("   ");

            ReportExportResponse response = service.requestExport(request, OPERATOR);

            assertThat(service.downloadExport(response.getExportId()).filename())
                    .isEqualTo("tax-liability-2026-01-01-2026-03-31.csv");
        }
    }

    @Nested
    @DisplayName("requestExport - PDF rendering")
    class PdfRendering {

        @Test
        @DisplayName("PDF sends the deterministic CSV to pos-documents and stores the returned PDF bytes")
        void pdf_rendersViaDocumentService() {
            BalanceSheetReport report = new BalanceSheetReport();
            when(financialReportingService.generateBalanceSheet(END)).thenReturn(report);
            when(balanceSheetCsvRenderer.render(report)).thenReturn(CSV);
            when(documentRenderClient.renderPdfFromCsv("DEFAULT_STANDARD_TEMPLATE", CSV))
                    .thenReturn(PDF_BYTES);
            ReportExportRequest request = request("BALANCE_SHEET", ExportFormat.PDF);
            request.setFilename("board pack");

            ReportExportResponse response = service.requestExport(request, OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.COMPLETED);
            assertThat(response.getFormat()).isEqualTo(ExportFormat.PDF);
            ReportExportArtifact artifact = service.downloadExport(response.getExportId());
            assertThat(artifact.contentType()).isEqualTo("application/pdf");
            assertThat(artifact.filename()).isEqualTo("board_pack.pdf");
            assertThat(artifact.content()).isEqualTo(PDF_BYTES);
        }

        @Test
        @DisplayName("a pos-documents rejection records FAILED with the upstream status and no artifact")
        void pdf_rejectedByDocumentService_fails() {
            stubTaxLiabilityCsv(CSV);
            when(documentRenderClient.renderPdfFromCsv(anyString(), anyString()))
                    .thenThrow(new HttpClientErrorException(HttpStatus.CONTENT_TOO_LARGE));

            ReportExportResponse response = service.requestExport(request("TAX_LIABILITY", ExportFormat.PDF), OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.FAILED);
            assertThat(response.getDownloadUrl()).isNull();
            assertThat(response.getCompletedAt()).isEqualTo(T0);
            assertThat(response.getFailureReason())
                    .isEqualTo("PDF rendering failed (pos-documents returned 413); see service logs for exportId "
                            + response.getExportId());
            assertThatThrownBy(() -> service.downloadExport(response.getExportId()))
                    .isInstanceOf(ResponseStatusException.class)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
        }
    }

    @Nested
    @DisplayName("requestExport - failures")
    class Failures {

        @Test
        @DisplayName("an unsupported report type fails without generating any report")
        void unsupportedReportType_fails() {
            ReportExportResponse response = service.requestExport(request("JOURNAL_LINES", ExportFormat.CSV), OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.FAILED);
            assertThat(response.getReportType()).isEqualTo("JOURNAL_LINES");
            assertThat(response.getCompletedAt()).isEqualTo(T0);
            assertThat(response.getDownloadUrl()).isNull();
            assertThat(response.getFailureReason())
                    .isEqualTo("Rendering is not supported for reportType 'JOURNAL_LINES'");
            verifyNoInteractions(financialReportingService, documentRenderClient);
            // The failed job is still recorded and queryable.
            assertThat(service.getExportStatus(response.getExportId())).isSameAs(response);
        }

        @ParameterizedTest
        @EnumSource(
                value = ExportFormat.class,
                names = {"XLSX", "JSON"})
        @DisplayName("formats other than CSV and PDF fail without generating any report")
        void unsupportedFormat_fails(ExportFormat format) {
            ReportExportResponse response = service.requestExport(request("INCOME_STATEMENT", format), OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.FAILED);
            assertThat(response.getFormat()).isEqualTo(format);
            assertThat(response.getDownloadUrl()).isNull();
            assertThat(response.getFailureReason()).isEqualTo("Rendering is not supported for format '" + format + "'");
            verifyNoInteractions(financialReportingService, documentRenderClient);
        }

        @Test
        @DisplayName("a report generation error records FAILED with a generic reason and no artifact")
        void generationError_fails() {
            when(financialReportingService.generateIncomeStatement(any(), any()))
                    .thenThrow(new IllegalArgumentException("endDate before startDate"));

            ReportExportResponse response =
                    service.requestExport(request("INCOME_STATEMENT", ExportFormat.CSV), OPERATOR);

            assertThat(response.getStatus()).isEqualTo(ExportStatus.FAILED);
            assertThat(response.getDownloadUrl()).isNull();
            assertThat(response.getFailureReason())
                    .isEqualTo("Rendering failed; see service logs for exportId " + response.getExportId())
                    .doesNotContain("endDate before startDate");
            verify(incomeStatementCsvRenderer, never()).render(any());
        }
    }

    @Nested
    @DisplayName("getExportStatus")
    class GetExportStatus {

        @Test
        @DisplayName("returns the recorded job for a known exportId")
        void knownId_returnsJob() {
            stubTaxLiabilityCsv(CSV);
            ReportExportResponse created = exportTaxLiabilityCsv();

            assertThat(service.getExportStatus(created.getExportId())).isSameAs(created);
        }

        @Test
        @DisplayName("throws 404 for an unknown exportId")
        void unknownId_throws404() {
            UUID unknown = UUID.randomUUID();

            assertThatThrownBy(() -> service.getExportStatus(unknown))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No export found with ID: " + unknown)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("downloadExport")
    class DownloadExport {

        @Test
        @DisplayName("throws 404 for an unknown exportId")
        void unknownId_throws404() {
            UUID unknown = UUID.randomUUID();

            assertThatThrownBy(() -> service.downloadExport(unknown))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No export found with ID: " + unknown)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }

        @Test
        @DisplayName("throws 409 when the export did not complete")
        void failedExport_throws409() {
            ReportExportResponse failed = service.requestExport(request("UNKNOWN", ExportFormat.CSV), OPERATOR);

            assertThatThrownBy(() -> service.downloadExport(failed.getExportId()))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("is not COMPLETED (status: FAILED)")
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.CONFLICT));
        }

        @Test
        @DisplayName("evicts the least-recently-used artifact once more than MAX_ARTIFACTS are held")
        void artifactStore_evictsLeastRecentlyUsed() {
            stubTaxLiabilityCsv(CSV);
            List<UUID> ids = new ArrayList<>();
            for (int i = 0; i < ReportExportServiceImpl.MAX_ARTIFACTS; i++) {
                ids.add(exportTaxLiabilityCsv().getExportId());
            }
            // Touch the oldest so the second-oldest becomes least recently used.
            assertThat(service.downloadExport(ids.get(0))).isNotNull();

            UUID newest = exportTaxLiabilityCsv().getExportId();

            assertThat(service.downloadExport(ids.get(0)).content()).isEqualTo(CSV.getBytes(StandardCharsets.UTF_8));
            assertThat(service.downloadExport(newest)).isNotNull();
            UUID evicted = ids.get(1);
            // The job record survives eviction; only the artifact bytes are dropped.
            assertThat(service.getExportStatus(evicted).getStatus()).isEqualTo(ExportStatus.COMPLETED);
            assertThatThrownBy(() -> service.downloadExport(evicted))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("No artifact found for export ID: " + evicted)
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.NOT_FOUND));
        }
    }

    @Nested
    @DisplayName("getExportHistory")
    class GetExportHistory {

        private UUID first;
        private UUID second;
        private UUID third;

        @BeforeEach
        void seedThreeExports() {
            // Unsupported report types fail fast without touching collaborators.
            first = service.requestExport(request("A", ExportFormat.CSV), OPERATOR)
                    .getExportId();
            clock.set(T0.plusSeconds(60));
            second = service.requestExport(request("B", ExportFormat.CSV), OPERATOR)
                    .getExportId();
            clock.set(T0.plusSeconds(120));
            third = service.requestExport(request("C", ExportFormat.CSV), OPERATOR)
                    .getExportId();
        }

        private List<UUID> ids(Page<ReportExportResponse> page) {
            return page.getContent().stream()
                    .map(ReportExportResponse::getExportId)
                    .toList();
        }

        @Test
        @DisplayName("unsorted pageable defaults to newest first")
        void unsorted_newestFirst() {
            Page<ReportExportResponse> page = service.getExportHistory(PageRequest.of(0, 10));

            assertThat(ids(page)).containsExactly(third, second, first);
            assertThat(page.getTotalElements()).isEqualTo(3);
        }

        @Test
        @DisplayName("requestedAt ascending returns oldest first")
        void requestedAtAscending_oldestFirst() {
            Page<ReportExportResponse> page =
                    service.getExportHistory(PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "requestedAt")));

            assertThat(ids(page)).containsExactly(first, second, third);
        }

        @Test
        @DisplayName("requestedAt descending returns newest first")
        void requestedAtDescending_newestFirst() {
            Page<ReportExportResponse> page =
                    service.getExportHistory(PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "requestedAt")));

            assertThat(ids(page)).containsExactly(third, second, first);
        }

        @Test
        @DisplayName("pages are sliced by offset and page size with the full total")
        void pagination_slicesResults() {
            Page<ReportExportResponse> page0 = service.getExportHistory(PageRequest.of(0, 2));
            Page<ReportExportResponse> page1 = service.getExportHistory(PageRequest.of(1, 2));

            assertThat(ids(page0)).containsExactly(third, second);
            assertThat(ids(page1)).containsExactly(first);
            assertThat(page1.getTotalElements()).isEqualTo(3);
            assertThat(page1.getTotalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("an offset beyond the total returns an empty page that still reports the total")
        void offsetBeyondTotal_returnsEmptyPage() {
            Page<ReportExportResponse> page = service.getExportHistory(PageRequest.of(3, 1));

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isEqualTo(3);
        }

        @ParameterizedTest
        @ValueSource(strings = {"reportType", "completedAt"})
        @DisplayName("sorting by any property other than requestedAt is rejected with 400")
        void unsupportedSortProperty_throws400(String property) {
            PageRequest pageable = PageRequest.of(0, 10, Sort.by(property));

            assertThatThrownBy(() -> service.getExportHistory(pageable))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Unsupported sort property: '" + property + "'")
                    .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                            .isEqualTo(HttpStatus.BAD_REQUEST));
        }
    }

    @Test
    @DisplayName("an empty history returns an empty first page")
    void emptyHistory_returnsEmptyPage() {
        Page<ReportExportResponse> page = service.getExportHistory(PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    /** Settable clock so history ordering can be driven by distinct requestedAt values. */
    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
