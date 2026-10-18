package com.positivity.extractionworker.internal.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.TestCorpus;
import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.sandbox.SandboxRequest;
import com.positivity.extractionworker.internal.sandbox.SandboxResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The real child JVM: deadline kill, out-of-memory exit, environment, stderr and untrusted output. */
class ParseSandboxTest {

    static final String TEST_MAIN = SandboxTestMain.class.getName();

    @TempDir
    Path dir;

    static ExtractionWorkerProperties properties(String mainClass, String heap) {
        return new ExtractionWorkerProperties(
                "secret-value-that-must-not-reach-the-child",
                null,
                System.getProperty("java.io.tmpdir"),
                2,
                26_214_400L,
                new ExtractionWorkerProperties.Sandbox(heap, null, mainClass),
                new ExtractionWorkerProperties.Deadline(
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(120)),
                new ExtractionWorkerProperties.Pdf(30, 268_435_456L),
                new ExtractionWorkerProperties.Image(50_000_000L, 30),
                new ExtractionWorkerProperties.Spreadsheet(
                        10_000, 100, 1_000, 10_000, 104_857_600L, 209_715_200L, 0.01),
                new ExtractionWorkerProperties.Render(150, 25_000_000L),
                new ExtractionWorkerProperties.Heif("heif-dec", Duration.ofSeconds(20), 1024L));
    }

    private SandboxOutcome run(String operation, Duration deadline, String heap, String mainClass) {
        ParseSandbox sandbox = new ParseSandbox(properties(mainClass, heap));
        return sandbox.run(dir, new SandboxRequest(operation, DeclaredType.PDF, 0, TestCorpus.limits()), deadline);
    }

    @Test
    void realChildInspectsAPdfEndToEnd() throws IOException {
        Files.write(dir.resolve("input"), TestCorpus.pdf(3));

        SandboxOutcome outcome = run(
                "inspect",
                Duration.ofSeconds(30),
                "256m",
                "com.positivity.extractionworker.internal.sandbox.ParseMain");

        assertThat(outcome).isInstanceOf(SandboxOutcome.Completed.class);
        SandboxResult result = ((SandboxOutcome.Completed) outcome).result();
        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.ACCEPTED);
        assertThat(result.pageCount()).isEqualTo(3);
    }

    @Test
    void childThatOutlivesTheDeadlineIsKilled() {
        long started = System.nanoTime();

        SandboxOutcome outcome = run("sleep", Duration.ofSeconds(2), "64m", TEST_MAIN);

        assertThat(outcome).isInstanceOf(SandboxOutcome.TimedOut.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(20));
    }

    @Test
    void childThatRunsOutOfMemoryExitsAndIsReportedAsSuch() {
        SandboxOutcome outcome = run("oom", Duration.ofSeconds(30), "48m", TEST_MAIN);

        assertThat(outcome).isInstanceOf(SandboxOutcome.OutOfMemory.class);
    }

    @Test
    void childExitingNonZeroIsFailed() {
        assertThat(run("exit2", Duration.ofSeconds(30), "64m", TEST_MAIN)).isInstanceOf(SandboxOutcome.Failed.class);
    }

    @Test
    void unparseableOversizedOrNonsensicalResultsAreFailed() {
        assertThat(run("junk", Duration.ofSeconds(30), "64m", TEST_MAIN)).isInstanceOf(SandboxOutcome.Failed.class);
        assertThat(run("huge", Duration.ofSeconds(30), "64m", TEST_MAIN)).isInstanceOf(SandboxOutcome.Failed.class);
        assertThat(run("badpages", Duration.ofSeconds(30), "64m", TEST_MAIN)).isInstanceOf(SandboxOutcome.Failed.class);
    }

    @Test
    void childGetsOnlyPathJavaHomeAndLang() throws IOException {
        SandboxOutcome outcome = run("env", Duration.ofSeconds(30), "64m", TEST_MAIN);

        assertThat(outcome).isInstanceOf(SandboxOutcome.Completed.class);
        Set<String> names = Set.of(Files.readString(dir.resolve("env.txt")).split(","));
        assertThat(names).isSubsetOf("PATH", "JAVA_HOME", "LANG");
        assertThat(names).doesNotContain("AWS_ACCESS_KEY_ID", "POS_EXTRACTION_WORKER_API_SECRET", "HOME");
    }

    @Test
    void childOutputIsDiscardedAndLeavesNoFileBehind() throws IOException {
        SandboxOutcome outcome = run("stderr", Duration.ofSeconds(30), "64m", TEST_MAIN);

        assertThat(outcome).isInstanceOf(SandboxOutcome.Completed.class);
        // stdout and stderr go nowhere: no file on the tmpfs to fill, nothing to read or log.
        try (var files = Files.list(dir)) {
            assertThat(files.map(path -> path.getFileName().toString())).doesNotContain("child.out");
        }
    }

    @Test
    void childThatCannotStartIsUnavailableNotAVerdict() {
        SandboxOutcome outcome = run("inspect", Duration.ofSeconds(30), "64m", "no.such.MainClass");

        assertThat(outcome).isInstanceOf(SandboxOutcome.Unavailable.class);
    }

    @Test
    void unwritableCallDirectoryIsUnavailable() {
        ParseSandbox sandbox = new ParseSandbox(properties(TEST_MAIN, "64m"));

        SandboxOutcome outcome = sandbox.run(
                dir.resolve("does-not-exist"),
                new SandboxRequest("inspect", DeclaredType.PDF, 0, TestCorpus.limits()),
                Duration.ofSeconds(5));

        assertThat(outcome).isInstanceOf(SandboxOutcome.Unavailable.class);
    }
}
