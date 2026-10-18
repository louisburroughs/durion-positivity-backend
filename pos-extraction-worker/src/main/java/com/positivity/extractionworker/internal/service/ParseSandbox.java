package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.sandbox.ParseMain;
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
 * secret, no AWS variable), killed at the deadline. Its stdout and stderr go to a file in the call
 * directory that is deleted and never read: a library's exception message can quote file content.
 *
 * <p>The child's result is untrusted. It must be a small, parseable {@link SandboxResult}; anything else
 * is {@link SandboxOutcome.Failed}.
 */
@Component
@Slf4j
public class ParseSandbox {

    /** A result file larger than this is treated as hostile output. */
    static final long MAX_RESULT_BYTES = 4096;

    private static final long KILL_WAIT_SECONDS = 5;
    private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

    private final ExtractionWorkerProperties properties;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ParseSandbox(@NonNull ExtractionWorkerProperties properties) {
        this.properties = properties;
    }

    @NonNull SandboxOutcome run(@NonNull Path callDir, @NonNull SandboxRequest request, @NonNull Duration deadline) {
        Process process = null;
        try {
            mapper.writeValue(callDir.resolve(ParseMain.REQUEST_FILE).toFile(), request);
            ProcessBuilder builder = new ProcessBuilder(command(callDir, request));
            builder.environment().clear();
            builder.environment().put("PATH", pathOrDefault());
            builder.environment().put("LANG", "C.UTF-8");
            String javaHome = System.getenv("JAVA_HOME");
            if (javaHome != null) {
                builder.environment().put("JAVA_HOME", javaHome);
            }
            builder.directory(callDir.toFile());
            builder.redirectErrorStream(true);
            builder.redirectOutput(callDir.resolve("child.out").toFile());
            process = builder.start();
            process.getOutputStream().close();
            if (!process.waitFor(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                kill(process);
                return new SandboxOutcome.TimedOut();
            }
            return interpret(callDir, process.exitValue());
        } catch (IOException e) {
            log.warn("Could not run the parse sandbox ({})", e.getClass().getSimpleName());
            return new SandboxOutcome.Failed();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (process != null) {
                kill(process);
            }
            return new SandboxOutcome.Failed();
        }
    }

    private SandboxOutcome interpret(Path callDir, int exit) {
        if (exit == 3) {
            return new SandboxOutcome.OutOfMemory();
        }
        if (exit != 0) {
            return new SandboxOutcome.Failed();
        }
        Path resultFile = callDir.resolve(ParseMain.RESULT_FILE);
        try {
            if (!Files.isRegularFile(resultFile) || Files.size(resultFile) > MAX_RESULT_BYTES) {
                return new SandboxOutcome.Failed();
            }
            SandboxResult result = mapper.readValue(resultFile.toFile(), SandboxResult.class);
            return result == null || result.outcome() == null || result.pageCount() < 0
                    ? new SandboxOutcome.Failed()
                    : new SandboxOutcome.Completed(result);
        } catch (IOException | JacksonException e) {
            return new SandboxOutcome.Failed();
        }
    }

    private List<String> command(Path callDir, SandboxRequest request) {
        ExtractionWorkerProperties.Sandbox sandbox = properties.sandbox();
        String classpath = sandbox.childClasspath() == null || sandbox.childClasspath().isBlank()
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

    private static void kill(Process process) throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(KILL_WAIT_SECONDS, TimeUnit.SECONDS);
    }
}
