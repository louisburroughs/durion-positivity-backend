package com.positivity.order;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Source-level rule: do not hardcode hasAuthority('...') literals in controller
 * @PreAuthorize annotations. Use permission constants instead.
 */
class PermissionStyleTest {

    @Test
    void controllers_should_use_permission_constants_in_preauthorize() throws IOException {
        Path controllerRoot = Path.of("src/main/java/com/positivity/order/internal/controller");
        if (!Files.exists(controllerRoot)) {
            return;
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(controllerRoot)) {
            files.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> inspectFile(path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                "Found hardcoded hasAuthority literals in @PreAuthorize. Use constants, e.g. "
                        + "@PreAuthorize(\"hasAuthority('\" + SomePermissions.SOME_PERMISSION + \"')\").\n"
                        + String.join("\n", violations));
    }

    private static void inspectFile(Path path, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(path);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.contains("@PreAuthorize(\"hasAuthority('") && !line.contains("+")) {
                    violations.add(path + ":" + (i + 1) + " -> " + line.trim());
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to inspect " + path, e);
        }
    }
}
