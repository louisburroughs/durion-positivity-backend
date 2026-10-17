package com.positivity.archunit;

import java.nio.file.Files;
import java.nio.file.Path;

/** Repository-walk helpers shared by the source-scanning architecture tests. */
final class RepoLayout {

    private RepoLayout() {}

    /** Whether {@code dir} is a {@code pos-*} Maven module directory. */
    static boolean isPosModule(Path dir) {
        return Files.isDirectory(dir)
                && dir.getFileName().toString().startsWith("pos-")
                && Files.exists(dir.resolve("pom.xml"));
    }

    /** The repository root: the nearest ancestor of the working directory that holds {@code pos-archunit}. */
    static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("pos-archunit")) && Files.exists(dir.resolve("pom.xml"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("repository root with pos-archunit not found above "
                + Path.of("").toAbsolutePath());
    }
}
