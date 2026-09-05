package com.positivity.archunit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Filesystem analysis behind {@link GlobalExceptionHandlerEnforcementTest}, extracted so the
 * rule itself is testable against fixtures rather than only against the live tree (issue #1717).
 *
 * <p>ADR-0056 §4 says the platform catch-all is "enforced rather than remembered". Before #1717
 * the enforcement checked only that a module <em>declares</em> an
 * {@code @ExceptionHandler(Exception.class)} advice, regardless of what that advice returns — so
 * {@code pos-people} and {@code pos-people-contact} passed the build while answering 500s as
 * bare {@code ProblemDetail} with no {@code code} and no {@code correlationId}, exactly what
 * ADR-0017 §3/§4 forbid and what the rule exists to prevent.
 */
final class CatchAllAdviceScanner {

    /** Matches the catch-all declaration and the handler signature that follows it. */
    private static final Pattern CATCH_ALL_HANDLER = Pattern.compile(
            "@ExceptionHandler\\(\\s*Exception\\.class\\s*\\)" // the declaration
                    + "(?:\\s*@\\w[\\w.]*(?:\\([^)]*\\))?)*" // any further annotations on the method
                    + "\\s*(?:public|protected|private)?\\s*" // optional visibility modifier
                    + "([^;{]*?)\\s*\\w+\\s*\\(", // the return type, up to the method name
            Pattern.DOTALL);

    /** Java line and block comments, stripped before matching so tombstone prose is not a hit. */
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*.*?\\*/", Pattern.DOTALL);

    private CatchAllAdviceScanner() {}

    /** A module-local catch-all and the type its handler method answers with. */
    record CatchAll(String module, String file, String returnType) {

        /**
         * ADR-0017 §3 makes {@code ApiError} the envelope for every non-2xx body. A catch-all
         * answering anything else — {@code ProblemDetail}, a raw {@code Map} — is the shape the
         * rule is meant to reject.
         */
        boolean returnsApiError() {
            return returnType.contains("ApiError");
        }
    }

    /** Module directories that declare at least one {@code @RestController}. */
    static List<Path> controllerModules(Path repoRoot, Set<String> exemptModules) throws IOException {
        try (Stream<Path> modules = Files.list(repoRoot)) {
            return modules.filter(module -> module.getFileName().toString().startsWith("pos-"))
                    .filter(module -> Files.isDirectory(module.resolve("src/main/java")))
                    .filter(module ->
                            !exemptModules.contains(module.getFileName().toString()))
                    .filter(module -> anyMainSourceContains(module, "@RestController"))
                    .sorted()
                    .toList();
        }
    }

    /** Every module-local catch-all in {@code module}, with the type each one answers with. */
    static List<CatchAll> catchAllsIn(Path module) {
        String moduleName = module.getFileName().toString();
        List<CatchAll> found = new ArrayList<>();
        forEachMainSource(module, path -> {
            String source = COMMENTS.matcher(read(path)).replaceAll(" ");
            Matcher matcher = CATCH_ALL_HANDLER.matcher(source);
            while (matcher.find()) {
                found.add(new CatchAll(
                        moduleName,
                        module.relativize(path).toString(),
                        matcher.group(1).trim()));
            }
        });
        return found;
    }

    /** Whether the module's pom declares a dependency that supplies the shared catch-all. */
    static boolean dependsOnCatchAllProvider(Path module, Set<String> providers) {
        Path pom = module.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return false;
        }
        String content = read(pom);
        return providers.stream().anyMatch(provider -> content.contains("<artifactId>" + provider + "</artifactId>"));
    }

    private static boolean anyMainSourceContains(Path module, String needle) {
        boolean[] hit = {false};
        forEachMainSource(module, path -> {
            if (!hit[0] && read(path).contains(needle)) {
                hit[0] = true;
            }
        });
        return hit[0];
    }

    private static void forEachMainSource(Path module, java.util.function.Consumer<Path> action) {
        Path sourceRoot = module.resolve("src/main/java");
        if (!Files.isDirectory(sourceRoot)) {
            return;
        }
        try (Stream<Path> sources = Files.walk(sourceRoot)) {
            sources.filter(path -> path.toString().endsWith(".java")).forEach(action);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot walk " + sourceRoot, e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + path, e);
        }
    }
}
