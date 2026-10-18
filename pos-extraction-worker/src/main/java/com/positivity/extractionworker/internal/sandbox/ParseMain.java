package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the child JVM (S26 ruling 5). The web process starts one child per call, with a heap cap,
 * {@code -XX:+ExitOnOutOfMemoryError} and an emptied environment, and kills it at the deadline. The child
 * reads {@code <callDir>/input} and {@code request.json}, writes {@code result.json} (and
 * {@code page.png} for a render) and exits 0. An out-of-memory error ends the JVM with exit code 3 before
 * anything is written; the parent reads that as a resource-limit breach. Exit code 4 means the child itself
 * failed after starting (it could not read its request or write its result); exit code 1 is the JVM's own
 * failure to launch this class.
 *
 * <p>This is the only place the parsers of hostile input (PDFBox, POI, ImageIO, the HEIC decoder) are
 * reached from, and the web process may not call it: it talks to the child only through
 * {@link SandboxRequest}, {@link SandboxResult} and {@link SandboxLimits}. Nothing here logs: the parent
 * discards stdout and stderr, and a library's exception message can quote file content.
 */
public final class ParseMain {

    /** Exit code for a child that started but could not do its job (not a verdict on the file). */
    static final int EXIT_INTERNAL_FAILURE = 4;

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ParseMain() {}

    public static void main(String[] args) {
        preferToBeKilledFirst();
        int exit = EXIT_INTERNAL_FAILURE;
        try {
            if (args.length == 2) {
                run(Path.of(args[1]));
                exit = 0;
            }
        } catch (IOException | RuntimeException e) {
            exit = EXIT_INTERNAL_FAILURE;
        }
        System.exit(exit);
    }

    /**
     * Makes the kernel's out-of-memory killer pick this process (and the HEIC decoder it starts) before the
     * web JVM. Best effort: the file does not exist off Linux, and raising the score needs no privilege.
     */
    private static void preferToBeKilledFirst() {
        try {
            Files.writeString(Path.of("/proc/self/oom_score_adj"), "1000");
        } catch (IOException | RuntimeException e) {
            // not Linux, or not permitted: nothing to do
        }
    }

    /** Runs the request found in {@code callDir} and writes {@code result.json}. */
    static void run(Path callDir) throws IOException {
        SandboxRequest request =
                MAPPER.readValue(callDir.resolve(SandboxRequest.REQUEST_FILE).toFile(), SandboxRequest.class);
        SandboxResult result = execute(callDir, request);
        MAPPER.writeValue(callDir.resolve(SandboxRequest.RESULT_FILE).toFile(), result);
    }

    /** Dispatches one request. Anything the parsers throw becomes an unreadable-file result. */
    static SandboxResult execute(Path callDir, SandboxRequest request) {
        Path input = callDir.resolve(SandboxRequest.INPUT_FILE);
        boolean render = SandboxRequest.RENDER.equals(request.operation());
        try {
            return render ? render(callDir, input, request) : inspect(callDir, input, request);
        } catch (StackOverflowError | IOException | RuntimeException e) {
            return render ? SandboxResult.notRenderable() : SandboxResult.rejected(ReasonCode.UNREADABLE);
        }
    }

    private static SandboxResult inspect(Path callDir, Path input, SandboxRequest request) throws IOException {
        SandboxLimits limits = request.limits();
        DeclaredType type = request.declaredType();
        return switch (type) {
            case PDF -> PdfInspector.inspect(input, limits);
            case JPEG, PNG, TIFF -> ImageInspector.inspect(input, type, limits);
            case HEIC -> {
                HeicDecoder.Decoded decoded = HeicDecoder.decode(input, callDir, limits);
                if (decoded.unavailable()) {
                    yield SandboxResult.unavailable();
                }
                yield decoded.failure() != null
                        ? SandboxResult.rejected(decoded.failure())
                        : ImageInspector.inspect(decoded.jpeg(), DeclaredType.JPEG, limits);
            }
            case XLSX -> OoxmlInspector.inspect(input, limits);
            case CSV -> CsvInspector.inspect(input, limits);
        };
    }

    private static SandboxResult render(Path callDir, Path input, SandboxRequest request) throws IOException {
        SandboxLimits limits = request.limits();
        Path out = callDir.resolve(SandboxResult.RENDERED_FILE);
        DeclaredType type = request.declaredType();
        if (!type.isRenderable() || request.page() < 1) {
            return SandboxResult.notRenderable();
        }
        return switch (type) {
            case PDF -> PdfRenderer.render(input, request.page(), limits, out);
            case JPEG, PNG, TIFF -> ImageRenderer.render(input, type, request.page(), limits, out);
            case HEIC -> {
                HeicDecoder.Decoded decoded = HeicDecoder.decode(input, callDir, limits);
                if (decoded.unavailable()) {
                    yield SandboxResult.unavailable();
                }
                yield decoded.failure() != null
                        ? SandboxResult.notRenderable()
                        : ImageRenderer.render(decoded.jpeg(), DeclaredType.JPEG, request.page(), limits, out);
            }
            default -> SandboxResult.notRenderable();
        };
    }
}
