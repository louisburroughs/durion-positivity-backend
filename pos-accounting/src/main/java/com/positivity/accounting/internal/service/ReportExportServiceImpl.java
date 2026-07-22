package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.client.DocumentRenderClient;
import com.positivity.accounting.internal.dto.ReportExportArtifact;
import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.dto.TaxLiabilityReport;
import com.positivity.accounting.internal.enums.ExportFormat;
import com.positivity.accounting.internal.enums.ExportStatus;
import com.positivity.accounting.service.FinancialReportingService;
import com.positivity.accounting.service.ReportExportService;
import com.positivity.security.common.LogSanitizer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
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
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link ReportExportService} with real rendering (issue #999).
 *
 * <p>Exports render synchronously at request time: CSV is produced locally by
 * {@link TaxLiabilityCsvRenderer} (deterministic column/row order, figures matching
 * the JSON report to the cent) and PDF is produced by the pos-documents service per
 * ADR-0020 (the deterministic CSV is sent as render content). Successful renders
 * complete immediately with a download URL; failures are recorded as FAILED with a
 * reason. Job state and artifacts are persisted in-memory (JPA entity + object
 * storage deferred to a future capability sprint).
 */
@Service
public class ReportExportServiceImpl implements ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportServiceImpl.class);

    /** The only report type with rendering support so far (story T8). */
    static final String TAX_LIABILITY = "TAX_LIABILITY";

    private static final String PDF_TEMPLATE_ID = "DEFAULT_STANDARD_TEMPLATE";

    private final Clock clock;
    private final FinancialReportingService financialReportingService;
    private final TaxLiabilityCsvRenderer csvRenderer;
    private final DocumentRenderClient documentRenderClient;

    /**
     * In-memory stores for export jobs and rendered artifacts (replaced by JPA
     * entity + object storage in a future sprint).
     */
    private final ConcurrentHashMap<UUID, ReportExportResponse> exportStore = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, ReportExportArtifact> artifactStore = new ConcurrentHashMap<>();

    public ReportExportServiceImpl(
            Clock clock,
            FinancialReportingService financialReportingService,
            TaxLiabilityCsvRenderer csvRenderer,
            DocumentRenderClient documentRenderClient) {
        this.clock = clock;
        this.financialReportingService = financialReportingService;
        this.csvRenderer = csvRenderer;
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

        if (!TAX_LIABILITY.equals(request.getReportType())) {
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
            TaxLiabilityReport report =
                    financialReportingService.generateTaxLiability(request.getStartDate(), request.getEndDate());
            String csv = csvRenderer.render(report);
            ReportExportArtifact artifact = toArtifact(request, csv);
            artifactStore.put(exportId, artifact);
            return builder.status(ExportStatus.COMPLETED)
                    .completedAt(Instant.now(clock))
                    .downloadUrl("/v1/accounting/reports/export/" + exportId + "/download")
                    .build();
        } catch (RuntimeException e) {
            log.warn("Report export rendering failed: exportId={} reason={}", exportId, e.getMessage());
            return builder.status(ExportStatus.FAILED)
                    .completedAt(Instant.now(clock))
                    .failureReason("Rendering failed: " + e.getMessage())
                    .build();
        }
    }

    private ReportExportArtifact toArtifact(ReportExportRequest request, String csv) {
        String baseName =
                (request.getFilename() != null && !request.getFilename().isBlank())
                        ? request.getFilename()
                        : "tax-liability-" + request.getStartDate() + "-" + request.getEndDate();
        if (request.getFormat() == ExportFormat.CSV) {
            return new ReportExportArtifact(csv.getBytes(StandardCharsets.UTF_8), "text/csv", baseName + ".csv");
        }
        byte[] pdf = documentRenderClient.renderPdfFromCsv(PDF_TEMPLATE_ID, csv);
        return new ReportExportArtifact(pdf, "application/pdf", baseName + ".pdf");
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
}
