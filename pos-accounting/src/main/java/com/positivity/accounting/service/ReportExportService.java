package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.ReportExportRequest;
import com.positivity.accounting.internal.dto.ReportExportResponse;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Public API for asynchronous report export operations.
 *
 * <p>
 * Consumers (other modules, controllers) interact exclusively through this
 * interface. Implementation details are encapsulated in
 * {@code internal/service}.
 */
public interface ReportExportService {

  /**
   * Submit a new asynchronous report export request.
   *
   * @param request    validated export request payload
   * @param operatorId authenticated operator submitting the request
   * @return initial export job response with PENDING status
   */
  @NonNull
  ReportExportResponse requestExport(@NonNull ReportExportRequest request, @NonNull String operatorId);

  /**
   * Get the current status of an export job.
   *
   * @param exportId export job UUID
   * @return current export job state
   * @throws org.springframework.web.server.ResponseStatusException with HTTP 404
   *                                                                when no export
   *                                                                exists for the
   *                                                                given ID
   */
  @NonNull
  ReportExportResponse getExportStatus(@NonNull UUID exportId);

  /**
   * List export history (paginated, most recent first).
   *
   * @param pageable pagination and sort parameters
   * @return page of export job responses
   */
  @NonNull
  Page<ReportExportResponse> getExportHistory(@NonNull Pageable pageable);
}
