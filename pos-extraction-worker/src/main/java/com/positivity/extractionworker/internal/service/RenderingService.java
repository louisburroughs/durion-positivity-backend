package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import com.positivity.extractionworker.internal.exception.InvalidRequestException;
import com.positivity.extractionworker.internal.exception.PageNotRenderableException;
import com.positivity.extractionworker.internal.exception.WorkerBusyException;
import com.positivity.extractionworker.internal.exception.WorkerUnavailableException;
import com.positivity.extractionworker.internal.sandbox.SandboxRequest;
import com.positivity.extractionworker.internal.sandbox.SandboxResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * {@code render}: one page of a PDF, JPEG, PNG, HEIC or TIFF as PNG, on demand. The page is drawn in the child
 * JVM; the parent checks the PNG it gets back (signature, header dimensions, size) without decoding it.
 * Anything that stops a page being produced answers 422 {@code PAGE_NOT_RENDERABLE}.
 */
@Service
@Slf4j
public class RenderingService {

    static final String OPERATION = "render";
    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    /** Header bytes needed: signature, IHDR length and type, then width and height. */
    private static final int PNG_HEADER_BYTES = 24;

    private final ExtractionWorkerProperties properties;
    private final ScratchSpace scratch;
    private final ParsePool pool;
    private final ParseSandbox sandbox;
    private final ExtractionMetrics metrics;

    public RenderingService(
            @NonNull ExtractionWorkerProperties properties,
            @NonNull ScratchSpace scratch,
            @NonNull ParsePool pool,
            @NonNull ParseSandbox sandbox,
            @NonNull ExtractionMetrics metrics) {
        this.properties = properties;
        this.scratch = scratch;
        this.pool = pool;
        this.sandbox = sandbox;
        this.metrics = metrics;
    }

    /**
     * @param upload the uploaded bytes
     * @param declaredType PDF, JPEG, PNG, HEIC or TIFF
     * @param page the 1-based page
     * @param tenant the caller's tenant id, a label only
     * @return the PNG bytes
     */
    public byte @NonNull [] render(
            @NonNull InputStream upload, @NonNull DeclaredType declaredType, int page, @NonNull String tenant) {
        if (!declaredType.isRenderable()) {
            throw new InvalidRequestException("declaredType", "Only PDF, JPEG, PNG, HEIC and TIFF files render");
        }
        if (page < 1) {
            throw new InvalidRequestException("page", "The page is 1-based and must be at least 1");
        }
        long started = System.nanoTime();
        if (!pool.tryAcquire()) {
            metrics.record(tenant, OPERATION, "BUSY", null, Duration.ofNanos(System.nanoTime() - started));
            throw new WorkerBusyException();
        }
        String outcome = "NOT_RENDERABLE";
        ReasonCode reason = null;
        String sha256 = null;
        try (ScratchSpace.CallDir dir = scratch.open()) {
            UploadStager.Staged staged =
                    UploadStager.stage(upload, dir.path().resolve("input"), properties.maxFileBytes());
            sha256 = staged.sha256();
            DeclaredType detected = MagicBytes.detect(staged.head(), staged.head().length);
            if (staged.size() == 0 || detected != declaredType) {
                throw new PageNotRenderableException();
            }
            SandboxRequest request =
                    new SandboxRequest(SandboxRequest.RENDER, declaredType, page, properties.sandboxLimits());
            SandboxOutcome run =
                    sandbox.run(dir.path(), request, properties.deadline().render());
            if (run instanceof SandboxOutcome.Unavailable) {
                outcome = "UNAVAILABLE";
                throw new WorkerUnavailableException();
            }
            if (run instanceof SandboxOutcome.TimedOut || run instanceof SandboxOutcome.OutOfMemory) {
                reason = ReasonCode.RESOURCE_LIMIT_EXCEEDED;
                throw new PageNotRenderableException();
            }
            if (!(run instanceof SandboxOutcome.Completed completed)
                    || completed.result().outcome() != SandboxResult.Outcome.RENDERED) {
                throw new PageNotRenderableException();
            }
            byte[] png = readValidatedPng(dir.path().resolve(SandboxResult.RENDERED_FILE));
            outcome = "RENDERED";
            return png;
        } catch (IOException e) {
            log.warn("Could not stage the upload ({})", e.getClass().getSimpleName());
            outcome = "UNAVAILABLE";
            throw new WorkerUnavailableException();
        } finally {
            pool.release();
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            metrics.record(tenant, OPERATION, outcome, reason, elapsed);
            log.info(
                    "render tenant={} sha256={} declaredType={} page={} outcome={} reason={} durationMs={}",
                    tenant,
                    sha256,
                    declaredType,
                    page,
                    outcome,
                    reason,
                    elapsed.toMillis());
        }
    }

    /** The child's PNG is untrusted: a real PNG header, dimensions within the cap, a bounded size. */
    private byte[] readValidatedPng(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            throw new PageNotRenderableException();
        }
        long size = Files.size(file);
        if (size < PNG_HEADER_BYTES || size > properties.maxFileBytes() * 2) {
            throw new PageNotRenderableException();
        }
        byte[] png = Files.readAllBytes(file);
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (png[i] != PNG_SIGNATURE[i]) {
                throw new PageNotRenderableException();
            }
        }
        ByteBuffer header = ByteBuffer.wrap(png, 16, 8);
        long width = header.getInt() & 0xFFFFFFFFL;
        long height = header.getInt() & 0xFFFFFFFFL;
        long cap = properties.render().maxPixels();
        if (width == 0 || height == 0 || width * height > cap + cap / 20) {
            throw new PageNotRenderableException();
        }
        return png;
    }
}
