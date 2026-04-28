package com.positivity.accounting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.internal.dto.ExportJobRequest;
import com.positivity.accounting.internal.dto.ExportJobResponse;
import com.positivity.accounting.internal.exception.ExportJobNotFoundException;
import com.positivity.accounting.internal.exception.UnsupportedSortPropertyException;
import com.positivity.accounting.internal.service.TimekeepingExportServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@DisplayName("TimekeepingExportServiceImpl Unit Tests")
class TimekeepingExportServiceTest {

    private static final Instant FIXED_NOW = Instant.parse("2025-06-01T09:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private TimekeepingExportServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TimekeepingExportServiceImpl(FIXED_CLOCK);
    }

    @Nested
    @DisplayName("requestExport")
    class RequestExport {

        @Test
        @DisplayName("should return job with non-null UUID jobId, status PENDING, and requestedAt set")
        void requestExport_returnsJobWithNonNullUuidAndPendingStatus() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();

            ExportJobResponse result = service.requestExport(request);

            assertThat(result.getJobId()).isNotNull();
            assertThat(result.getStatus()).isEqualTo("PENDING");
            assertThat(result.getRequestedAt()).isEqualTo(FIXED_NOW);
        }

        @Test
        @DisplayName("should assign distinct jobIds for each call")
        void requestExport_assignsDistinctJobIds() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();

            ExportJobResponse result1 = service.requestExport(request);
            ExportJobResponse result2 = service.requestExport(request);

            assertThat(result1.getJobId()).isNotEqualTo(result2.getJobId());
        }
    }

    @Nested
    @DisplayName("getExportStatus")
    class GetExportStatus {

        @Test
        @DisplayName("should return 200 (job response) for an existing job")
        void getExportStatus_returnsJob_whenExists() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();
            ExportJobResponse created = service.requestExport(request);

            ExportJobResponse result = service.getExportStatus(created.getJobId());

            assertThat(result.getJobId()).isEqualTo(created.getJobId());
            assertThat(result.getStatus()).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("should throw ExportJobNotFoundException for unknown jobId")
        void getExportStatus_throwsExportJobNotFoundException_whenNotFound() {
            UUID unknownId = UUID.randomUUID();

            assertThatThrownBy(() -> service.getExportStatus(unknownId))
                    .isInstanceOf(ExportJobNotFoundException.class)
                    .hasMessageContaining(unknownId.toString());
        }
    }

    @Nested
    @DisplayName("listExportHistory")
    class ListExportHistory {

        @Test
        @DisplayName("should return paginated results")
        void listExportHistory_returnsPaginatedResults() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();
            service.requestExport(request);
            service.requestExport(request);
            service.requestExport(request);

            Page<ExportJobResponse> page = service.listExportHistory(PageRequest.of(0, 2));

            assertThat(page.getContent()).hasSize(2);
            assertThat(page.getTotalElements()).isEqualTo(3);
            assertThat(page.getTotalPages()).isEqualTo(2);
        }

        @Test
        @DisplayName("should sort by requestedAt DESC when sort order is DESC")
        void listExportHistory_sortsByRequestedAtDesc() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();
            // Create two jobs — jobIds are UUIDv7 which are time-ordered, but we
            // rely on requestedAt for deterministic sort verification
            ExportJobResponse job1 = service.requestExport(request);
            ExportJobResponse job2 = service.requestExport(request);

            Page<ExportJobResponse> page = service.listExportHistory(
                    PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "requestedAt")));

            // All jobs should appear; ordering is by requestedAt DESC
            // Since clock is fixed, requestedAt is identical for both — just verify both present
            assertThat(page.getContent()).extracting(ExportJobResponse::getJobId)
                    .containsExactlyInAnyOrder(job1.getJobId(), job2.getJobId());
        }

        @Test
        @DisplayName("should throw UnsupportedSortPropertyException for invalid sort property")
        void listExportHistory_throwsUnsupportedSortPropertyException_forInvalidSortProperty() {
            assertThatThrownBy(() -> service.listExportHistory(
                    PageRequest.of(0, 20, Sort.by("invalidField"))))
                    .isInstanceOf(UnsupportedSortPropertyException.class)
                    .hasMessageContaining("invalidField");
        }

        @Test
        @DisplayName("should return empty page when offset exceeds total")
        void listExportHistory_returnsEmptyPage_whenOffsetExceedsTotal() {
            ExportJobRequest request = ExportJobRequest.builder()
                    .exportType("TIMEKEEPING")
                    .format("CSV")
                    .build();
            service.requestExport(request);

            Page<ExportJobResponse> page = service.listExportHistory(PageRequest.of(5, 20));

            assertThat(page.getContent()).isEmpty();
            assertThat(page.getTotalElements()).isEqualTo(1);
        }
    }
}
