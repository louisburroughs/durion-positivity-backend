package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.dto.InspectionResponse;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import com.positivity.extractionworker.internal.enums.Verdict;
import com.positivity.extractionworker.internal.exception.WorkerBusyException;
import com.positivity.extractionworker.internal.sandbox.SandboxRequest;
import com.positivity.extractionworker.internal.sandbox.SandboxResult;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@code inspect}: the verdict on one upload. The web process streams the upload to scratch, hashes it and
 * checks size and magic bytes; everything that parses structure runs in the child JVM
 * ({@link ParseSandbox}). Log lines carry only the allowed fields: tenant, sha256, size, types, page count,
 * operation, outcome, reason and duration; never a file name, a value or a library message.
 */
@Service
@Slf4j
public class InspectionService {

    static final String OPERATION = "inspect";
    private static final String UNKNOWN_TYPE = "UNKNOWN";

    private final ExtractionWorkerProperties properties;
    private final ScratchSpace scratch;
    private final ParsePool pool;
    private final ParseSandbox sandbox;
    private final ExtractionMetrics metrics;

    public InspectionService(
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
     * @param declaredType the type the caller says the file is
     * @param tenant the caller's tenant id, a label only
     */
    public @NonNull InspectionResponse inspect(
            @NonNull InputStream upload, @NonNull DeclaredType declaredType, @NonNull String tenant) {
        long started = System.nanoTime();
        if (!pool.tryAcquire()) {
            metrics.record(tenant, OPERATION, "BUSY", null, Duration.ofNanos(System.nanoTime() - started));
            throw new WorkerBusyException();
        }
        try (ScratchSpace.CallDir dir = scratch.open()) {
            UploadStager.Staged staged =
                    UploadStager.stage(upload, dir.path().resolve("input"), properties.maxFileBytes());
            InspectionResponse response = decide(dir, staged, declaredType);
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            metrics.record(tenant, OPERATION, response.verdict().name(), response.reasonCode(), elapsed);
            log.info(
                    "inspect tenant={} sha256={} size={} declaredType={} detectedType={} pageCount={} verdict={}"
                            + " reason={} durationMs={}",
                    tenant,
                    response.sha256(),
                    staged.size(),
                    declaredType,
                    response.detectedType(),
                    response.pageCount(),
                    response.verdict(),
                    response.reasonCode(),
                    elapsed.toMillis());
            return response;
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Could not stage the upload", new IOException(e.getClass().getSimpleName()));
        } finally {
            pool.release();
        }
    }

    private InspectionResponse decide(ScratchSpace.CallDir dir, UploadStager.Staged staged, DeclaredType declaredType) {
        if (staged.size() == 0) {
            return rejected(ReasonCode.UNREADABLE, UNKNOWN_TYPE, staged);
        }
        if (declaredType != DeclaredType.CSV) {
            DeclaredType detected = MagicBytes.detect(staged.head(), staged.head().length);
            if (detected != declaredType) {
                return rejected(ReasonCode.TYPE_MISMATCH, detected == null ? UNKNOWN_TYPE : detected.name(), staged);
            }
        }
        SandboxRequest request =
                new SandboxRequest(SandboxRequest.INSPECT, declaredType, 0, properties.sandboxLimits());
        SandboxOutcome outcome =
                sandbox.run(dir.path(), request, properties.deadline().inspect());
        return switch (outcome) {
            case SandboxOutcome.TimedOut t -> rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED, declaredType.name(), staged);
            case SandboxOutcome.OutOfMemory o ->
                rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED, declaredType.name(), staged);
            case SandboxOutcome.Failed f -> rejected(ReasonCode.UNREADABLE, declaredType.name(), staged);
            case SandboxOutcome.Completed c -> fromChild(c.result(), declaredType, staged);
        };
    }

    /** Validates the child's answer before it becomes a response: it processed hostile input. */
    private InspectionResponse fromChild(SandboxResult result, DeclaredType declaredType, UploadStager.Staged staged) {
        if (result.outcome() == SandboxResult.Outcome.ACCEPTED
                && result.reasonCode() == null
                && result.pageCount() <= maxPlausiblePages()) {
            return new InspectionResponse(
                    Verdict.ACCEPTED, null, declaredType.name(), result.pageCount(), staged.sha256());
        }
        if (result.outcome() == SandboxResult.Outcome.REJECTED && result.reasonCode() != null) {
            String detected = result.reasonCode() == ReasonCode.TYPE_MISMATCH ? UNKNOWN_TYPE : declaredType.name();
            return rejected(result.reasonCode(), detected, staged);
        }
        return rejected(ReasonCode.UNREADABLE, declaredType.name(), staged);
    }

    private int maxPlausiblePages() {
        return Math.max(properties.spreadsheet().maxRows(), properties.pdf().maxPages());
    }

    private static InspectionResponse rejected(
            @NonNull ReasonCode reason, @Nullable String detectedType, UploadStager.Staged staged) {
        return new InspectionResponse(
                Verdict.REJECTED, reason, detectedType == null ? UNKNOWN_TYPE : detectedType, 0, staged.sha256());
    }
}
