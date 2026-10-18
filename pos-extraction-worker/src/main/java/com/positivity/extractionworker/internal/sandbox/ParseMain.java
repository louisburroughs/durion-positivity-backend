package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/**
 * Entry point of the child JVM (S26 ruling 5). The web process starts one child per call, with a heap cap,
 * {@code -XX:+ExitOnOutOfMemoryError} and an emptied environment, and kills it at the deadline. The child
 * reads {@code <callDir>/input} and {@code request.json}, writes {@code result.json} (and
 * {@code page.png} for a render) and exits 0. An out-of-memory error ends the JVM with exit code 3 before
 * anything is written; the parent reads that as a resource-limit breach.
 *
 * <p>This is the only place the parsers of hostile input (PDFBox, POI, ImageIO, the HEIC decoder) are
 * reached from. Nothing here logs: stdout and stderr are discarded by the parent, and a library's
 * exception message can quote file content.
 */
public final class ParseMain {

    public static final String INPUT_FILE = "input";
    public static final String REQUEST_FILE = "request.json";
    public static final String RESULT_FILE = "result.json";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private ParseMain() {}

    public static void main(String[] args) {
        int exit = 1;
        try {
            if (args.length == 2) {
                run(Path.of(args[1]));
                exit = 0;
            }
        } catch (IOException | RuntimeException e) {
            exit = 1;
        }
        System.exit(exit);
    }

    /** Runs the request found in {@code callDir} and writes {@code result.json}. */
    public static void run(Path callDir) throws IOException {
        SandboxRequest request = MAPPER.readValue(callDir.resolve(REQUEST_FILE).toFile(), SandboxRequest.class);
        SandboxResult result = execute(callDir, request);
        MAPPER.writeValue(callDir.resolve(RESULT_FILE).toFile(), result);
    }

    /** Dispatches one request. Anything the parsers throw becomes an unreadable-file result. */
    public static SandboxResult execute(Path callDir, SandboxRequest request) {
        Path input = callDir.resolve(INPUT_FILE);
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
                yield decoded.failure() != null
                        ? SandboxResult.notRenderable()
                        : ImageRenderer.render(decoded.jpeg(), DeclaredType.JPEG, request.page(), limits, out);
            }
            default -> SandboxResult.notRenderable();
        };
    }
}
