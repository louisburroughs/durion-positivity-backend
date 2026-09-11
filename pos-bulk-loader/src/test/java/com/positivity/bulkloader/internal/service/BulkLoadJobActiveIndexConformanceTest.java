package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.bulkloader.internal.enums.JobStatus;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Copilot review of PR #1955, third round (Finding 3): {@link BulkLoadJobServiceImpl#ACTIVE_STATUSES}
 * is the Java side of "does this operator already have an active job"; {@code
 * idx_bulk_load_job_one_active_per_operator} in {@code V1__baseline_bulk_loader.sql} is the
 * database's own enforcement of the identical rule, as a partial unique index excluding exactly the
 * statuses that are not active. The two must name the same set. They previously did not: the index
 * excluded only {@code COMPLETED}/{@code CANCELLED}/{@code FAILED} and still treated {@code PARTIAL}
 * as active, so a create the service's own check let through (correctly — a partial run is done,
 * see {@link BulkLoadJobServiceImpl#TERMINAL_STATUSES}) failed at the database with an opaque
 * constraint violation instead.
 *
 * <p>This test parses the migration's own {@code WHERE} clause rather than hand-copying its
 * status list, so a future edit to either side that drifts from the other fails here — at the
 * point of the edit — instead of at a bulk-load operator's keyboard.
 */
class BulkLoadJobActiveIndexConformanceTest {

    private static final Pattern INDEX_DDL =
            Pattern.compile("CREATE UNIQUE INDEX idx_bulk_load_job_one_active_per_operator\\b.*?;", Pattern.DOTALL);
    private static final Pattern EXCLUDED_STATUS = Pattern.compile("'([A-Z_]+)'::character varying");

    @Test
    @DisplayName("the migration's active-job index excludes exactly ACTIVE_STATUSES' complement")
    void indexExclusionListMatchesActiveStatusesComplement() throws IOException {
        String migration = readMigration();
        Matcher indexMatcher = INDEX_DDL.matcher(migration);
        assertThat(indexMatcher.find())
                .as("idx_bulk_load_job_one_active_per_operator must still be defined in the baseline migration")
                .isTrue();
        String indexDdl = indexMatcher.group();

        Set<JobStatus> excludedByIndex = EnumSet.noneOf(JobStatus.class);
        Matcher statusMatcher = EXCLUDED_STATUS.matcher(indexDdl);
        while (statusMatcher.find()) {
            excludedByIndex.add(JobStatus.valueOf(statusMatcher.group(1)));
        }
        assertThat(excludedByIndex)
                .as("the index's WHERE clause must have found at least one excluded status")
                .isNotEmpty();

        assertThat(excludedByIndex)
                .as("the index must exclude exactly BulkLoadJobServiceImpl.TERMINAL_STATUSES — the statuses"
                        + " ACTIVE_STATUSES does not count as active — so the service's own"
                        + " one-active-job-per-operator check and the database's constraint agree on every"
                        + " status, including PARTIAL")
                .isEqualTo(BulkLoadJobServiceImpl.TERMINAL_STATUSES);

        Set<JobStatus> everyStatus = EnumSet.allOf(JobStatus.class);
        Set<JobStatus> impliedActive = EnumSet.copyOf(everyStatus);
        impliedActive.removeAll(excludedByIndex);
        assertThat(impliedActive)
                .as("sanity: every JobStatus is either active or excluded by the index, never neither nor both")
                .isEqualTo(EnumSet.copyOf(BulkLoadJobServiceImpl.ACTIVE_STATUSES));
    }

    private String readMigration() throws IOException {
        try (InputStream in =
                getClass().getClassLoader().getResourceAsStream("db/migration/V1__baseline_bulk_loader.sql")) {
            assertThat(in)
                    .as("db/migration/V1__baseline_bulk_loader.sql must be on the test classpath")
                    .isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
