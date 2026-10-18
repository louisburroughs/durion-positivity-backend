package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.sandbox.SandboxRequest;
import com.positivity.extractionworker.internal.sandbox.SandboxResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Start-up self-test: runs the real child JVM on a tiny bundled PNG and asks the HEIC decoder for its version.
 * A failure flips this health indicator DOWN; it never fails start-up, so an operator sees a worker whose
 * sandbox cannot run (broken classpath, missing {@code heif-dec}) instead of finding out as 503s (S26
 * ruling 2: an infrastructure fault is never a verdict on a file).
 */
@Component
@Slf4j
public class WorkerSelfTest implements HealthIndicator {

    private static final long HEIF_PROBE_SECONDS = 5;

    private final ExtractionWorkerProperties properties;
    private final ScratchSpace scratch;
    private final ParseSandbox sandbox;
    private volatile Health health =
            Health.unknown().withDetail("selfTest", "not run yet").build();

    public WorkerSelfTest(
            @NonNull ExtractionWorkerProperties properties,
            @NonNull ScratchSpace scratch,
            @NonNull ParseSandbox sandbox) {
        this.properties = properties;
        this.scratch = scratch;
        this.sandbox = sandbox;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        boolean sandboxOk = sandboxWorks();
        boolean heifOk = heifPresent();
        Health.Builder builder = sandboxOk && heifOk ? Health.up() : Health.down();
        health = builder.withDetail("sandbox", sandboxOk ? "ok" : "failed")
                .withDetail("heif", heifOk ? "ok" : "missing")
                .build();
        if (sandboxOk && heifOk) {
            log.info("Self-test passed: parse sandbox and HEIC decoder work");
        } else {
            log.warn("Self-test failed: sandbox={} heif={}", sandboxOk ? "ok" : "failed", heifOk ? "ok" : "missing");
        }
    }

    public boolean sandboxWorks() {
        try (ScratchSpace.CallDir dir = scratch.open();
                InputStream png = getClass().getResourceAsStream("/selftest.png")) {
            if (png == null) {
                return false;
            }
            Files.write(dir.path().resolve(SandboxRequest.INPUT_FILE), png.readAllBytes());
            SandboxRequest request =
                    new SandboxRequest(SandboxRequest.INSPECT, DeclaredType.PNG, 0, properties.sandboxLimits());
            SandboxOutcome outcome =
                    sandbox.run(dir.path(), request, properties.deadline().inspect());
            return outcome instanceof SandboxOutcome.Completed completed
                    && completed.result().outcome() == SandboxResult.Outcome.ACCEPTED
                    && completed.result().pageCount() == 1;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    boolean heifPresent() {
        try {
            Process process = new ProcessBuilder(properties.heif().binary(), "--version")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(HEIF_PROBE_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public Health health() {
        return health;
    }
}
