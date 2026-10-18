package com.positivity.extractionworker.internal.sandbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.positivity.extractionworker.TestCorpus;
import com.positivity.extractionworker.internal.enums.DeclaredType;
import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * HEIC needs libheif's {@code heif-dec} (S26 ruling 6). The checks that run before the native decoder run
 * everywhere; the decode itself runs only where the binary is on PATH and the committed sample exists, and is
 * skipped otherwise (buildbox has no libheif).
 */
class HeicDecoderTest {

    @TempDir
    Path dir;

    /** A file that looks like HEIC to the magic check and carries one {@code ispe} box. */
    private static byte[] heicDeclaring(int width, int height) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(
                new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1'});
        out.writeBytes(new byte[] {0, 0, 0, 20, 'i', 's', 'p', 'e', 0, 0, 0, 0});
        out.writeBytes(ByteBuffer.allocate(8).putInt(width).putInt(height).array());
        return out.toByteArray();
    }

    private SandboxResult inspect(byte[] bytes, String binary) throws IOException {
        Files.write(dir.resolve(SandboxRequest.INPUT_FILE), bytes);
        SandboxLimits limits = new SandboxLimits(
                30,
                30,
                50_000_000L,
                25_000_000L,
                150,
                10_000,
                100,
                1_000,
                10_000,
                104_857_600L,
                209_715_200L,
                0.01,
                binary,
                 20, 1024, 268_435_456L);
        return ParseMain.execute(dir, new SandboxRequest(SandboxRequest.INSPECT, DeclaredType.HEIC, 0, limits));
    }

    @Test
    void oversizedDeclaredDimensionsAreRefusedBeforeTheNativeDecoderRuns() throws IOException {
        // The decoder binary does not exist: reaching it would be UNREADABLE, not a resource-limit breach.
        SandboxResult result = inspect(heicDeclaring(100_000, 100_000), "no-such-heif-decoder");

        assertThat(result.reasonCode()).isEqualTo(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void largestDeclaredPixelsReadsEveryIspeBoxAndSurvivesOverflow() throws IOException {
        Path file = dir.resolve("probe");
        Files.write(file, heicDeclaring(4000, 3000));
        assertThat(HeicDecoder.largestDeclaredPixels(file)).isEqualTo(12_000_000L);

        Files.write(file, heicDeclaring(-1, -1));
        assertThat(HeicDecoder.largestDeclaredPixels(file)).isGreaterThan(50_000_000L);
    }

    @Test
    void missingDecoderBinaryIsInfrastructureNotAVerdictOnTheFile() throws IOException {
        SandboxResult result = inspect(heicDeclaring(64, 64), "no-such-heif-decoder");

        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.UNAVAILABLE);
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void heicWithNoReadableSizeFailsClosedBeforeTheDecoder() throws IOException {
        byte[] noIspe = new byte[] {0, 0, 0, 24, 'f', 't', 'y', 'p', 'h', 'e', 'i', 'c', 0, 0, 0, 0, 'm', 'i', 'f', '1'};

        SandboxResult result = inspect(noIspe, "no-such-heif-decoder");

        assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.REJECTED);
        assertThat(result.reasonCode()).isEqualTo(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
    }

    @Test
    void realHeicDecodesWhenTheToolAndTheSampleArePresent() throws IOException {
        assumeTrue(onPath("heif-dec"), "heif-dec is not installed: HEIC decode test skipped");
        try (InputStream sample = getClass().getResourceAsStream("/heic/tiny.heic")) {
            assumeTrue(sample != null, "no committed HEIC sample: HEIC decode test skipped");
            byte[] bytes = sample.readAllBytes();

            SandboxResult result = inspect(bytes, "heif-dec");

            assertThat(result.outcome()).isEqualTo(SandboxResult.Outcome.ACCEPTED);
            assertThat(result.pageCount()).isEqualTo(1);
        }
        assertThat(TestCorpus.limits().heifBinary()).isEqualTo("heif-dec");
    }

    private static boolean onPath(String binary) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String entry : path.split(java.io.File.pathSeparator)) {
            if (Files.isExecutable(Path.of(entry, binary))) {
                return true;
            }
        }
        return false;
    }
}
