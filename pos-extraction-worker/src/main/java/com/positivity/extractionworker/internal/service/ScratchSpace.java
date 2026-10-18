package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * Per-call private working directories under {@code pos.extraction-worker.scratch-dir} (a tmpfs in
 * Compose). Each call gets one directory (mode 0700 on POSIX) that is deleted recursively when the call
 * ends, whatever the outcome (AC 9). Directories a crashed process left behind are swept at start-up; the
 * sweep touches only names this class creates, so a misconfigured scratch directory is never emptied.
 */
@Component
@Slf4j
public class ScratchSpace {

    static final String PREFIX = "xw-";

    private final Path root;

    public ScratchSpace(@NonNull ExtractionWorkerProperties properties) throws IOException {
        this.root = Path.of(properties.scratchDir());
        Files.createDirectories(root);
        sweep();
    }

    /** A new private directory; close it to delete it. */
    public CallDir open() throws IOException {
        return new CallDir(Files.createTempDirectory(root, PREFIX));
    }

    /** The scratch root, for tests and the README's AC 9 check. */
    public Path root() {
        return root;
    }

    private void sweep() throws IOException {
        try (Stream<Path> leftovers = Files.list(root)) {
            leftovers
                    .filter(path -> path.getFileName().toString().startsWith(PREFIX))
                    .forEach(ScratchSpace::deleteQuietly);
        }
    }

    private static void deleteQuietly(Path directory) {
        try {
            deleteRecursively(directory);
        } catch (IOException | UncheckedIOException e) {
            log.warn("Could not delete scratch directory ({})", e.getClass().getSimpleName());
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** One call's working directory. */
    public static final class CallDir implements AutoCloseable {
        private final Path path;

        private CallDir(Path path) {
            this.path = path;
        }

        public Path path() {
            return path;
        }

        @Override
        public void close() {
            deleteQuietly(path);
        }
    }
}
