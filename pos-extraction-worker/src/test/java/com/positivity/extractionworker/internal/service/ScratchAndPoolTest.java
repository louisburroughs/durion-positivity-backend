package com.positivity.extractionworker.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.health.contributor.Status;

class ScratchAndPoolTest {

    @TempDir
    Path root;

    private ExtractionWorkerProperties properties(int concurrent) {
        return new ExtractionWorkerProperties(
                null,
                root.toString(),
                concurrent,
                26_214_400L,
                new ExtractionWorkerProperties.Sandbox("64m", null, "x"),
                new ExtractionWorkerProperties.Deadline(
                        Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new ExtractionWorkerProperties.Pdf(30),
                new ExtractionWorkerProperties.Image(50_000_000L, 30),
                new ExtractionWorkerProperties.Spreadsheet(10_000, 100, 1_000, 10_000, 1L, 1L, 0.01),
                new ExtractionWorkerProperties.Render(150, 25_000_000L),
                new ExtractionWorkerProperties.Heif("heif-dec", Duration.ofSeconds(20)));
    }

    @Test
    void closingACallDirectoryDeletesItAndEverythingInIt() throws IOException {
        ScratchSpace scratch = new ScratchSpace(properties(2));

        Path created;
        try (ScratchSpace.CallDir dir = scratch.open()) {
            created = dir.path();
            Files.createDirectories(created.resolve("a/b"));
            Files.writeString(created.resolve("a/b/file"), "x");
            Files.writeString(created.resolve("input"), "y");
            assertThat(created).exists();
        }

        assertThat(created).doesNotExist();
        assertThat(root).isEmptyDirectory();
    }

    @Test
    void startupSweepRemovesOnlyDirectoriesThisClassCreates() throws IOException {
        Path leftover = Files.createDirectories(root.resolve("xw-leftover/nested"));
        Files.writeString(leftover.resolve("file"), "x");
        Path unrelated = Files.createDirectories(root.resolve("keep-me"));

        new ScratchSpace(properties(2));

        assertThat(root.resolve("xw-leftover")).doesNotExist();
        assertThat(unrelated).exists();
    }

    @Test
    void callDirectoriesAreDistinct() throws IOException {
        ScratchSpace scratch = new ScratchSpace(properties(2));

        try (ScratchSpace.CallDir one = scratch.open();
                ScratchSpace.CallDir two = scratch.open()) {
            assertThat(one.path()).isNotEqualTo(two.path());
        }
    }

    @Test
    void poolAdmitsUpToItsCapacityWithoutWaitingAndReportsSaturationAsUp() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ParsePool pool = new ParsePool(properties(2), registry);
        ParsePoolHealthIndicator health = new ParsePoolHealthIndicator(pool);

        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.tryAcquire()).isFalse();
        assertThat(pool.inUse()).isEqualTo(2);
        assertThat(registry.get("extraction.pool.in_use").gauge().value()).isEqualTo(2.0);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(health.health().getDetails()).containsEntry("saturated", true).containsEntry("capacity", 2);

        pool.release();

        assertThat(pool.tryAcquire()).isTrue();
        assertThat(pool.capacity()).isEqualTo(2);
    }
}
