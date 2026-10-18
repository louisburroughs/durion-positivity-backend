package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.sandbox.SandboxRequest;
import com.positivity.extractionworker.internal.sandbox.SandboxResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs one parse in a child JVM (S26 ruling 5). The web process never parses file structure: a thread stuck
 * in PDFBox cannot be stopped safely, and an OutOfMemoryError leaves a shared heap undefined. So each call
 * gets its own JVM with a heap cap, {@code -XX:+ExitOnOutOfMemoryError} and an emptied environment (no
 * secret, no AWS variable), killed at the deadline. Its stdout and stderr are discarded and its logging
 * is off: a library's exception message can quote file content.
 *
 * <p>The child's result is untrusted. It must be a small, parseable {@link SandboxResult}; anything else
 * is {@link SandboxOutcome.Failed}. A child that cannot start, or a decoder that is missing, is
 * {@link SandboxOutcome.Unavailable}: infrastructure, never a verdict on the file.
 */
@Component
@Slf4j
public class ParseSandbox {

    /** A result file larger than this is treated as hostile output. */
    static final long MAX_RESULT_BYTES = 4096;

    private static final long KILL_WAIT_SECONDS = 5;
    private static final int EXIT_JVM_LAUNCH_FAILURE = 1;
    private static final int EXIT_OUT_OF_MEMORY = 3;
    private static final int EXIT_KILLED = 137;
    private static final int EXIT_TERMINATED = 143;
    private static final String CHILD_LOGBACK = "child-logback.xml";
    private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

    private final ExtractionWorkerProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ParseSandbox(@NonNull ExtractionWorkerProperties properties) {
        this.properties = properties;
    }

    @NonNull
    @NonNull SandboxOutcome run(@NonNull Path callDir, @NonNull SandboxRequest request, @NonNull Duration deadline) {
        Process process = null;
        try {
            mapper.writeValue(callDir.resolve(SandboxRequest.REQUEST_FILE).toFile(), request);
            ProcessBuilder builder = new ProcessBuilder(command(callDir, request));
            builder.environment().clear();
            builder.environment().put("PATH", pathOrDefault());
            builder.environment().put("LANG", "C.UTF-8");
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                builder.environment().put("JAVA_HOME", javaHome);
            }
            builder.directory(callDir.toFile());
            // Nothing the child prints is kept: no file on the tmpfs to fill, nothing to read or log.
            builder.redirectErrorStream(true);
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                return new SandboxOutcome.TimedOut();
            }
            return interpret(callDir, process.exitValue());
        } catch (IOException | JacksonException e) {
            log.warn("Could not run the parse sandbox ({})", e.getClass().getSimpleName());
            return new SandboxOutcome.Unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new SandboxOutcome.Unavailable();
        } finally {
            if (process != null && process.isAlive()) {
                kill(process);
            }
        }
    }

    private SandboxOutcome interpret(Path callDir, int exit) {
        if (exit == EXIT_OUT_OF_MEMORY || exit == EXIT_KILLED || exit == EXIT_TERMINATED) {
            // ExitOnOutOfMemoryError, or the kernel's OOM killer (the child asks to be its first victim).
            return new SandboxOutcome.OutOfMemory();
        }
        if (exit == EXIT_JVM_LAUNCH_FAILURE) {
            // The JVM itself could not start the child class: a broken classpath or install, not a bad file.
            return new SandboxOutcome.Unavailable();
        }
        if (exit != 0) {
            return new SandboxOutcome.Failed();
        }
        Path resultFile = callDir.resolve(SandboxRequest.RESULT_FILE);
        try {
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > MAX_RESULT_BYTES) {
                return new SandboxOutcome.Failed();
            }
            SandboxResult result = mapper.readValue(resultFile.toFile(), SandboxResult.class);
            if (result == null || result.outcome() == null || result.pageCount() < 0) {
                return new SandboxOutcome.Failed();
            }
            return result.outcome() == SandboxResult.Outcome.UNAVAILABLE
                    ? new SandboxOutcome.Unavailable()
                    : new SandboxOutcome.Completed(result);
        } catch (IOException | JacksonException e) {
            return new SandboxOutcome.Failed();
        }
    }

    private List<String> command(Path callDir, SandboxRequest request) {
        ExtractionWorkerProperties.Sandbox sandbox = properties.sandbox();
        String classpath =
                sandbox.childClasspath() == null || sandbox.childClasspath().isBlank()
                        ? System.getProperty("java.class.path")
                        : sandbox.childClasspath();
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx" + sandbox.childHeap());
        command.add("-Xss2m");
        command.add("-XX:MaxMetaspaceSize=256m");
        command.add("-XX:+ExitOnOutOfMemoryError");
        command.add("-XX:ActiveProcessorCount=1");
        command.add("-XX:-UsePerfData");
        command.add("-Djava.awt.headless=true");
        command.add("-Dlogback.configurationFile=" + CHILD_LOGBACK);
        command.add("-Djava.io.tmpdir=" + callDir.toAbsolutePath());
        command.add("-cp");
        command.add(classpath);
        command.add(sandbox.childMainClass());
        command.add(request.operation());
        command.add(callDir.toAbsolutePath().toString());
        return command;
    }

    private static String pathOrDefault() {
        String path = System.getenv("PATH");
        return path == null || path.isBlank() ? DEFAULT_PATH : path;
    }

    private static void kill(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        try {
            process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
