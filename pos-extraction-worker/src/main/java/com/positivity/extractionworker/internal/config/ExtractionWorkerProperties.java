package com.positivity.extractionworker.internal.config;

import com.positivity.extractionworker.internal.sandbox.SandboxLimits;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code pos.extraction-worker.*}: the shared secret, the scratch and sandbox settings, the deadlines and
 * the limits. The README lists every setting with its default and what a breach answers.
 *
 * @param apiSecret the shared secret callers send as {@code X-Extraction-Worker-Secret}; a local-development
 *     fallback only, see {@link #resolvedSecret()}
 * @param apiSecretFile file holding the shared secret (Compose mounts it); wins over {@code apiSecret}
 * @param scratchDir the directory each call's private working directory is created under
 * @param maxConcurrentParses calls admitted at once; the next one is answered 503 {@code WORKER_BUSY}
 * @param maxFileBytes largest upload accepted; over it is 413 {@code FILE_TOO_LARGE}
 */
@ConfigurationProperties(prefix = "pos.extraction-worker")
public record ExtractionWorkerProperties(
        @Nullable String apiSecret,
        @Nullable String apiSecretFile,
        @DefaultValue("/scratch") String scratchDir,
        @DefaultValue("2") int maxConcurrentParses,
        @DefaultValue("26214400") long maxFileBytes,
        @DefaultValue Sandbox sandbox,
        @DefaultValue Deadline deadline,
        @DefaultValue Pdf pdf,
        @DefaultValue Image image,
        @DefaultValue Spreadsheet spreadsheet,
        @DefaultValue Render render,
        @DefaultValue Heif heif) {

    /**
     * @param childHeap the child JVM's {@code -Xmx}
     * @param childClasspath the child's classpath; blank means this JVM's own
     * @param childMainClass the child's entry point; only a test overrides it
     */
    public record Sandbox(
            @DefaultValue("512m") String childHeap,
            @Nullable String childClasspath,

            @DefaultValue("com.positivity.extractionworker.internal.sandbox.ParseMain")
            String childMainClass) {}

    /** Per-operation deadlines; the child is killed when one passes. */
    public record Deadline(
            @DefaultValue("PT30S") Duration inspect,
            @DefaultValue("PT30S") Duration render,
            @DefaultValue("PT30S") Duration tabulate,
            @DefaultValue("PT120S") Duration extract) {}

    /**
     * @param maxPages most pages a PDF may have
     * @param maxStreamCacheBytes most bytes PDFBox may spill to its temp-file stream cache
     */
    public record Pdf(
            @DefaultValue("30") int maxPages,
            @DefaultValue("268435456") long maxStreamCacheBytes) {}

    /**
     * @param maxPixels most pixels an image may declare in its header
     * @param maxTiffFrames most frames a TIFF may have
     */
    public record Image(
            @DefaultValue("50000000") long maxPixels,
            @DefaultValue("30") int maxTiffFrames) {}

    /**
     * @param maxRows most rows a CSV or first XLSX sheet may have
     * @param maxColumns most columns a row may have
     * @param maxCellChars most characters a cell may hold
     * @param maxZipEntries most entries an XLSX archive may hold
     * @param maxZipEntryBytes most bytes one entry may inflate to
     * @param maxZipTotalBytes most bytes the archive may inflate to
     * @param minInflateRatio smallest compressed to inflated ratio an entry may show
     */
    public record Spreadsheet(
            @DefaultValue("10000") int maxRows,
            @DefaultValue("100") int maxColumns,
            @DefaultValue("1000") int maxCellChars,
            @DefaultValue("10000") int maxZipEntries,
            @DefaultValue("104857600") long maxZipEntryBytes,
            @DefaultValue("209715200") long maxZipTotalBytes,
            @DefaultValue("0.01") double minInflateRatio) {}

    /**
     * @param dpi resolution a PDF page is rendered at
     * @param maxPixels most pixels in a rendered page; the resolution is lowered to fit
     */
    public record Render(
            @DefaultValue("150") int dpi,
            @DefaultValue("25000000") long maxPixels) {}

    /**
     * @param binary the HEIC decoder command
     * @param timeout deadline for the HEIC decoder
     * @param maxMemoryMb address-space limit ({@code ulimit -v}) the decoder runs under
     */
    public record Heif(
            @DefaultValue("heif-dec") String binary,
            @DefaultValue("PT20S") Duration timeout,
            @DefaultValue("1024") long maxMemoryMb) {}

    /** The limits the child enforces, in the form it reads from {@code request.json}. */
    public SandboxLimits sandboxLimits() {
        return new SandboxLimits(
                pdf.maxPages(),
                image.maxTiffFrames(),
                image.maxPixels(),
                render.maxPixels(),
                render.dpi(),
                spreadsheet.maxRows(),
                spreadsheet.maxColumns(),
                spreadsheet.maxCellChars(),
                spreadsheet.maxZipEntries(),
                spreadsheet.maxZipEntryBytes(),
                spreadsheet.maxZipTotalBytes(),
                spreadsheet.minInflateRatio(),
                heif.binary(),
                (int) heif.timeout().toSeconds(),
                heif.maxMemoryMb(),
                pdf.maxStreamCacheBytes());
    }

    /**
     * The shared secret. It comes from {@code api-secret-file} when that is set: a file is not part of
     * {@code /proc/<pid>/environ}, which every process of this uid (the parse children included) can read.
     * A file that is unreadable or blank yields a blank secret, so every request is refused (fail closed).
     * Only when no file is configured does the plain {@code api-secret} property apply, which is how local
     * development runs; Compose and alpha pass the file only.
     */
    public @Nullable String resolvedSecret() {
        if (apiSecretFile == null || apiSecretFile.isBlank()) {
            return apiSecret;
        }
        try {
            return Files.readString(Path.of(apiSecretFile), StandardCharsets.UTF_8)
                    .strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
