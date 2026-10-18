package com.positivity.extractionworker;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.extractionworker.internal.config.ExtractionWorkerProperties;
import com.positivity.extractionworker.internal.service.WorkerSelfTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class HardeningAndSecretTest {

    @Autowired
    ExtractionWorkerProperties properties;

    @Autowired
    WorkerSelfTest selfTest;

    @TempDir
    Path dir;

    private ExtractionWorkerProperties with(String secret, String file) {
        return new ExtractionWorkerProperties(
                secret,
                file,
                properties.scratchDir(),
                properties.maxConcurrentParses(),
                properties.maxFileBytes(),
                properties.sandbox(),
                properties.deadline(),
                properties.pdf(),
                properties.image(),
                properties.spreadsheet(),
                properties.render(),
                properties.heif());
    }

    @Test
    void secretFileWinsOverTheEnvironmentValueAndIsStripped() throws IOException {
        Path file = dir.resolve("secret");
        Files.writeString(file, "from-file\n");

        assertThat(with("from-env", file.toString()).resolvedSecret()).isEqualTo("from-file");
        assertThat(with("from-env", null).resolvedSecret()).isEqualTo("from-env");
        assertThat(with("from-env", "").resolvedSecret()).isEqualTo("from-env");
    }

    @Test
    void unreadableOrBlankSecretFileFailsClosed() throws IOException {
        Path blank = dir.resolve("blank");
        Files.writeString(blank, "  \n");

        assertThat(with("from-env", blank.toString()).resolvedSecret()).isBlank();
        assertThat(with("from-env", dir.resolve("missing").toString()).resolvedSecret())
                .isNull();
    }

    @Test
    void selfTestPassesTheSandboxHere() {
        assertThat(selfTest.sandboxWorks()).isTrue();
        selfTest.run();
        // heif-dec may be absent where this runs, so the aggregate can be DOWN; the sandbox must not be.
        assertThat(selfTest.health().getStatus()).isIn(Status.UP, Status.DOWN);
        assertThat(selfTest.health().getDetails()).containsEntry("sandbox", "ok");
    }
}
