package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import com.positivity.accounting.internal.enums.ExportStatus;
import com.positivity.accounting.service.ReportExportService;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

/**
 * Stub implementation of {@link ReportExportService}.
 *
 * <p>
 * Creates export jobs with PENDING status and persists them in-memory.
 * Actual async processing (S3 upload, job queue) is deferred to a future
 * capability sprint.
 */
@Service
public class ReportExportServiceImpl implements ReportExportService {

  private static final Logger log = LoggerFactory.getLogger(ReportExportServiceImpl.class);

  private final Clock clock;

  /**
   * In-memory store for export jobs (replaced by JPA entity in a future sprint).
   */
  private final ConcurrentHashMap<UUID, ReportExportResponse> exportStore = new ConcurrentHashMap<>();

  public ReportExportServiceImpl(Clock clock) {
    this.clock = clock;
  }

  @Override
  @NonNull
  public ReportExportResponse requestExport(@NonNull ReportExportRequest request, @NonNull String operatorId) {
    UUID exportId = UUID.randomUUID();
    Instant now = Instant.now(clock);

    ReportExportResponse response = ReportExportResponse.builder()
        .exportId(exportId)
        .status(ExportStatus.PENDING)
        .requestedAt(now)
        .format(request.getFormat())
        .reportType(request.getReportType())
        .build();

    exportStore.put(exportId, response);
    log.info("Report export requested: exportId={} reportType={} operator={}",
        exportId, request.getReportType(), operatorId);
    return response;
  }

  @Override
  @NonNull
  public ReportExportResponse getExportStatus(@NonNull UUID exportId) {
    ReportExportResponse response = exportStore.get(exportId);
    if (response == null) {
      throw new NoSuchElementException("No export found with ID: " + exportId);
    }
    return response;
  }

  @Override
  @NonNull
  public Page<ReportExportResponse> getExportHistory(@NonNull Pageable pageable) {
    var all = exportStore.values().stream()
        .sorted((a, b) -> b.getRequestedAt().compareTo(a.getRequestedAt()))
        .toList();

    int total = all.size();
    int start = (int) pageable.getOffset();
    int end = Math.min(start + pageable.getPageSize(), total);

    var page = (start >= total) ? java.util.List.<ReportExportResponse>of() : all.subList(start, end);
    return new PageImpl<>(page, pageable, total);
  }
}
