package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.DocumentRenderClient;
import com.positivity.accounting.internal.dto.ReportExportArtifact;
import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import com.positivity.accounting.service.FinancialReportingService;
import com.positivity.accounting.service.ReportExportService;
import com.positivity.security.common.LogSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link ReportExportService} with real rendering (issues #999,
 * #1011-#1015).
 *
 * <p>Exports render synchronously at request time: CSV is produced locally by the
 * per-report-type renderers (deterministic column/row order, figures matching the
 * JSON report to the cent) and PDF is produced by the pos-documents service per
 * ADR-0020 (the deterministic CSV is sent as render content). Successful renders
 * complete immediately with a download URL; failures are recorded as FAILED with a
 * reason. Job state and artifacts are persisted in-memory (JPA entity + object
 * storage deferred to a future capability sprint).
 *
 * <p><b>As-of-date mapping.</b> The export request carries {@code startDate} and
 * {@code endDate}; for the as-of reports (BALANCE_SHEET, TRIAL_BALANCE,
 * AGED_RECEIVABLES, AGED_PAYABLES) the request's {@code endDate} is used as the
 * as-of date (decision recorded on issues #1012/#1013/#1015 and documented in the
 * {@link ReportExportRequest} OpenAPI description).
 */
@Service
public class ReportExportServiceImpl implements ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportServiceImpl.class);

    static final String TAX_LIABILITY = "TAX_LIABILITY";
    static final String INCOME_STATEMENT = "INCOME_STATEMENT";
    static final String BALANCE_SHEET = "BALANCE_SHEET";
    static final String TRIAL_BALANCE = "TRIAL_BALANCE";
    static final String GENERAL_LEDGER = "GENERAL_LEDGER";
    static final String AGED_RECEIVABLES = "AGED_RECEIVABLES";
    static final String AGED_PAYABLES = "AGED_PAYABLES";

    /** Report types with rendering support (stories T8 #999 and #1011-#1015). */
    static final Set<String> SUPPORTED_REPORT_TYPES = Set.of(
            TAX_LIABILITY,
            INCOME_STATEMENT,
            BALANCE_SHEET,
            TRIAL_BALANCE,
            GENERAL_LEDGER,
            AGED_RECEIVABLES,
            AGED_PAYABLES);

    /**
     * As-of report types: rendering uses the request's {@code endDate} as the
     * as-of date, so default filenames carry only that date.
     */
    static final Set<String> AS_OF_REPORT_TYPES = Set.of(BALANCE_SHEET, TRIAL_BALANCE, AGED_RECEIVABLES, AGED_PAYABLES);

    private static final String PDF_TEMPLATE_ID = "DEFAULT_STANDARD_TEMPLATE";

    /**
     * Maximum rendered artifacts held in memory until object storage lands (issue
     * #999 follow-up). Oldest artifacts are evicted first; downloading an evicted
     * export returns 404.
     */
    static final int MAX_ARTIFACTS = 100;

    private final Clock clock;
    private final FinancialReportingService financialReportingService;
    private final TaxLiabilityCsvRenderer taxLiabilityCsvRenderer;
    private final IncomeStatementCsvRenderer incomeStatementCsvRenderer;
    private final BalanceSheetCsvRenderer balanceSheetCsvRenderer;
    private final TrialBalanceCsvRenderer trialBalanceCsvRenderer;
    private final GeneralLedgerCsvRenderer generalLedgerCsvRenderer;
    private final AgedReportCsvRenderer agedReportCsvRenderer;
    private final DocumentRenderClient documentRenderClient;

    /**
     * In-memory stores for export jobs and rendered artifacts (replaced by JPA
     * entity + object storage in a future sprint).
     */
    private final ConcurrentHashMap<UUID, ReportExportResponse> exportStore = new ConcurrentHashMap<>();

    /**
     * Bounded LRU store for rendered artifact bytes so full CSV/PDF payloads cannot
     * accumulate without limit (memory-pressure/DoS guard until object storage lands).
     */
    private final Map<UUID, ReportExportArtifact> artifactStore =
            Collections.synchronizedMap(new BoundedLruMap<>(MAX_ARTIFACTS));

    public ReportExportServiceImpl(
            Clock clock,
            FinancialReportingService financialReportingService,
            TaxLiabilityCsvRenderer taxLiabilityCsvRenderer,
            IncomeStatementCsvRenderer incomeStatementCsvRenderer,
            BalanceSheetCsvRenderer balanceSheetCsvRenderer,
            TrialBalanceCsvRenderer trialBalanceCsvRenderer,
            GeneralLedgerCsvRenderer generalLedgerCsvRenderer,
            AgedReportCsvRenderer agedReportCsvRenderer,
            DocumentRenderClient documentRenderClient) {
        this.clock = clock;
        this.financialReportingService = financialReportingService;
        this.taxLiabilityCsvRenderer = taxLiabilityCsvRenderer;
        this.incomeStatementCsvRenderer = incomeStatementCsvRenderer;
        this.balanceSheetCsvRenderer = balanceSheetCsvRenderer;
        this.trialBalanceCsvRenderer = trialBalanceCsvRenderer;
        this.generalLedgerCsvRenderer = generalLedgerCsvRenderer;
        this.agedReportCsvRenderer = agedReportCsvRenderer;
        this.documentRenderClient = documentRenderClient;
    }

    @Override
    @NonNull
    public ReportExportResponse requestExport(@NonNull ReportExportRequest request, @NonNull String operatorId) {
        UUID exportId = UUID.randomUUID();
        Instant now = Instant.now(clock);

        ReportExportResponse response = render(exportId, request, now);

        exportStore.put(exportId, response);
        log.info(
                "Report export requested: exportId={} reportType={} format={} status={} operator={}",
                exportId,
                LogSanitizer.forLog(request.getReportType()),
                request.getFormat(),
                response.getStatus(),
                LogSanitizer.forLog(operatorId));
        return response;
    }

    private ReportExportResponse render(UUID exportId, ReportExportRequest request, Instant requestedAt) {
        ReportExportResponse.ReportExportResponseBuilder builder = ReportExportResponse.builder()
                .exportId(exportId)
                .requestedAt(requestedAt)
                .format(request.getFormat())
                .reportType(request.getReportType());

        if (!SUPPORTED_REPORT_TYPES.contains(request.getReportType())) {
            return builder.status(ExportStatus.FAILED)
                    .completedAt(Instant.now(clock))
                    .failureReason("Rendering is not supported for reportType '" + request.getReportType() + "'")
                    .build();
        }
        if (request.getFormat() != ExportFormat.CSV && request.getFormat() != ExportFormat.PDF) {
            return builder.status(ExportStatus.FAILED)
                    .completedAt(Instant.now(clock))
                    .failureReason("Rendering is not supported for format '" + request.getFormat() + "'")
                    .build();
        }

        try {
            String csv = renderCsv(request);
            ReportExportArtifact artifact = toArtifact(request, csv);
            artifactStore.put(exportId, artifact);
            return builder.status(ExportStatus.COMPLETED)
                    .completedAt(Instant.now(clock))
                    .downloadUrl("/v1/accounting/reports/export/" + exportId + "/download")
                    .build();
        } catch (RestClientResponseException e) {
            // pos-documents rejected the render (e.g. payload over its configured
            // max-input-characters / max-table-rows limits) — fail cleanly, no 500.
            log.warn("Report export PDF rendering rejected by pos-documents: exportId={}", exportId, e);
            return builder.status(ExportStatus.FAILED)
                    .completedAt(Instant.now(clock))
                    .failureReason("PDF rendering failed (pos-documents returned "
                            + e.getStatusCode().value() + "); see service logs for exportId " + exportId)
                    .build();
        } catch (RuntimeException e) {
            log.warn("Report export rendering failed: exportId={}", exportId, e);
            return builder.status(ExportStatus.FAILED)
                    .completedAt(Instant.now(clock))
                    .failureReason("Rendering failed; see service logs for exportId " + exportId)
                    .build();
        }
    }

    /**
     * Generate the requested report and render it to deterministic CSV. As-of
     * reports use the request's {@code endDate} as the as-of date; the general
     * ledger honors the optional {@code accountId} filter.
     */
    private String renderCsv(ReportExportRequest request) {
        LocalDate start = request.getStartDate();
        LocalDate end = request.getEndDate();
        return switch (request.getReportType()) {
            case TAX_LIABILITY ->
                taxLiabilityCsvRenderer.render(financialReportingService.generateTaxLiability(start, end));
            case INCOME_STATEMENT ->
                incomeStatementCsvRenderer.render(financialReportingService.generateIncomeStatement(start, end));
            case BALANCE_SHEET -> balanceSheetCsvRenderer.render(financialReportingService.generateBalanceSheet(end));
            case TRIAL_BALANCE -> trialBalanceCsvRenderer.render(financialReportingService.generateTrialBalance(end));
            case GENERAL_LEDGER ->
                generalLedgerCsvRenderer.render(financialReportingService.generateGeneralLedger(
                        request.getAccountId() == null
                                ? null
                                : request.getAccountId().toString(),
                        start,
                        end));
            case AGED_RECEIVABLES ->
                agedReportCsvRenderer.render(financialReportingService.generateAgedReceivables(end));
            case AGED_PAYABLES -> agedReportCsvRenderer.render(financialReportingService.generateAgedPayables(end));
            default -> throw new IllegalStateException("Unexpected reportType '" + request.getReportType() + "'");
        };
    }

    private ReportExportArtifact toArtifact(ReportExportRequest request, String csv) {
        String baseName = sanitizeFilename(
                (request.getFilename() != null && !request.getFilename().isBlank())
                        ? request.getFilename()
                        : defaultBaseName(request));
        if (request.getFormat() == ExportFormat.CSV) {
            return new ReportExportArtifact(csv.getBytes(StandardCharsets.UTF_8), "text/csv", baseName + ".csv");
        }
        byte[] pdf = documentRenderClient.renderPdfFromCsv(PDF_TEMPLATE_ID, csv);
        return new ReportExportArtifact(pdf, "application/pdf", baseName + ".pdf");
    }

    private static String defaultBaseName(ReportExportRequest request) {
        String key = request.getReportType().toLowerCase(Locale.ROOT).replace('_', '-');
        if (AS_OF_REPORT_TYPES.contains(request.getReportType())) {
            return key + "-" + request.getEndDate();
        }
        return key + "-" + request.getStartDate() + "-" + request.getEndDate();
    }

    /**
     * Restrict the (potentially caller-supplied) download filename to a safe character
     * set so it can never carry CR/LF, quotes, or path separators into the
     * {@code Content-Disposition} header.
     */
    private static String sanitizeFilename(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "report-export" : cleaned;
    }

    @Override
    @NonNull
    public ReportExportResponse getExportStatus(@NonNull UUID exportId) {
        ReportExportResponse response = exportStore.get(exportId);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No export found with ID: " + exportId);
        }
        return response;
    }

    @Override
    @NonNull
    public ReportExportArtifact downloadExport(@NonNull UUID exportId) {
        ReportExportResponse response = exportStore.get(exportId);
        if (response == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No export found with ID: " + exportId);
        }
        if (response.getStatus() != ExportStatus.COMPLETED) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Export " + exportId + " is not COMPLETED (status: " + response.getStatus()
                            + "); no artifact is available");
        }
        ReportExportArtifact artifact = artifactStore.get(exportId);
        if (artifact == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No artifact found for export ID: " + exportId);
        }
        return artifact;
    }

    @Override
    @NonNull
    public Page<ReportExportResponse> getExportHistory(@NonNull Pageable pageable) {
        Sort sort = pageable.getSort();
        Comparator<ReportExportResponse> comparator;
        if (sort.isSorted()) {
            Sort.Order order = sort.iterator().next();
            if (!"requestedAt".equals(order.getProperty())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported sort property: '" + order.getProperty() + "'. Only 'requestedAt' is supported.");
            }
            comparator = order.isAscending()
                    ? Comparator.comparing(ReportExportResponse::getRequestedAt)
                    : (a, b) -> b.getRequestedAt().compareTo(a.getRequestedAt());
        } else {
            comparator = (a, b) -> b.getRequestedAt().compareTo(a.getRequestedAt());
        }

        var all = exportStore.values().stream().sorted(comparator).toList();

        int total = all.size();
        long offset = pageable.getOffset();
        if (offset >= total) {
            return new PageImpl<>(List.of(), pageable, total);
        }
        int start = Math.toIntExact(offset);
        int end = Math.toIntExact(Math.min(offset + pageable.getPageSize(), total));
        var page = all.subList(start, end);
        return new PageImpl<>(page, pageable, total);
    }

    /**
     * Access-order LRU map that evicts the eldest entry once {@code maxEntries} is
     * exceeded. Wrapped in {@link Collections#synchronizedMap} by the caller for
     * thread safety.
     */
    private static final class BoundedLruMap<K, V> extends LinkedHashMap<K, V> {

        private static final long serialVersionUID = 1L;

        private final int maxEntries;

        BoundedLruMap(int maxEntries) {
            super(16, 0.75f, true);
            this.maxEntries = maxEntries;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
            return size() > maxEntries;
        }
    }
}
