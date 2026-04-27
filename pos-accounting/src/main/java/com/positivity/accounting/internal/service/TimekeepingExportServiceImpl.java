package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import com.positivity.accounting.service.TimekeepingExportService;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class TimekeepingExportServiceImpl implements TimekeepingExportService {

  private final Clock clock;
  private final ConcurrentHashMap<UUID, ExportJobResponse> jobStore = new ConcurrentHashMap<>();

  @Override
  public @NonNull ExportJobResponse requestExport(@NonNull ExportJobRequest request) {
    UUID jobId = UUID.randomUUID();
    ExportJobResponse job = ExportJobResponse.builder()
        .jobId(jobId)
        .status("PENDING")
        .requestedAt(Instant.now(clock))
        .build();
    jobStore.put(jobId, job);
    return job;
  }

  @Override
  public @NonNull ExportJobResponse getExportStatus(@NonNull UUID jobId) {
    ExportJobResponse job = jobStore.get(jobId);
    if (job == null) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Export job not found: " + jobId);
    }
    return job;
  }

  @Override
  public @NonNull Page<ExportJobResponse> listExportHistory(@NonNull Pageable pageable) {
    Sort sort = pageable.getSort();
    Comparator<ExportJobResponse> comparator;
    if (sort.isSorted()) {
      Sort.Order order = sort.iterator().next();
      if (!"requestedAt".equals(order.getProperty())) {
        throw new ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "Unsupported sort property: '" + order.getProperty() + "'. Only 'requestedAt' is supported.");
      }
      comparator = order.isAscending()
          ? Comparator.comparing(ExportJobResponse::getRequestedAt)
          : (a, b) -> b.getRequestedAt().compareTo(a.getRequestedAt());
    } else {
      comparator = (a, b) -> b.getRequestedAt().compareTo(a.getRequestedAt());
    }

    List<ExportJobResponse> all = jobStore.values().stream().sorted(comparator).toList();

    int total = all.size();
    long offset = pageable.getOffset();
    if (offset >= total) {
      return new PageImpl<>(List.of(), pageable, total);
    }

    int start = Math.toIntExact(offset);
    int end = Math.toIntExact(Math.min(offset + pageable.getPageSize(), total));
    List<ExportJobResponse> page = all.subList(start, end);
    return new PageImpl<>(page, pageable, total);
  }
}
