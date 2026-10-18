package com.positivity.extractionworker.internal.sandbox;

import com.positivity.extractionworker.internal.enums.ReasonCode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;

/**
 * Decodes HEIC through libheif's command line tool, a grandchild process with its own timeout (S26
 * ruling 6): no pure-Java HEIC decoder exists. The image dimensions are read from the file's
 * {@code ispe} boxes first, so an oversized image (or one with no readable size) is refused before the native
 * decoder runs, and the decoder itself runs under {@code ulimit -v}. The output
 * is a JPEG in the call directory, which then takes the ordinary image path.
 */
final class HeicDecoder {

    private static final int SCAN_BYTES = 4 * 1024 * 1024;
    private static final byte[] ISPE = {'i', 's', 'p', 'e'};

    /**
     * The decoded JPEG, or why there is none. {@code unavailable} means the decoder itself could not run
     * (missing binary, no shell): an infrastructure fault, not a verdict on the file.
     */
    record Decoded(@Nullable Path jpeg, @Nullable ReasonCode failure, boolean unavailable) {
        static Decoded ok(Path jpeg) {
            return new Decoded(jpeg, null, false);
        }

        static Decoded rejected(ReasonCode reason) {
            return new Decoded(null, reason, false);
        }

        static Decoded missing() {
            return new Decoded(null, null, true);
        }
    }

    private HeicDecoder() {}

    static Decoded decode(Path input, Path callDir, SandboxLimits limits) throws IOException {
        // Fail closed: a HEIC with no readable ispe box cannot be sized, so it is not decoded.
        long declared = largestDeclaredPixels(input);
        if (declared == 0 || declared > limits.maxImagePixels()) {
            return Decoded.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
        }
        Path out = callDir.resolve("heic-decoded.jpg");
        // The native decoder runs under an address-space limit, so a hostile file cannot take the container's
        // memory. exec keeps the limit on heif-dec itself and lets a missing binary show as exit 127.
        ProcessBuilder builder = new ProcessBuilder(
                        "sh",
                        "-c",
                        "ulimit -v \"$0\" && exec \"$1\" \"$2\" \"$3\"",
                        Long.toString(limits.heifMaxMemoryMb() * 1024L),
                        limits.heifBinary(),
                        input.toAbsolutePath().toString(),
                        out.toAbsolutePath().toString())
                .directory(callDir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process process;
        try {
            process = builder.start();
        } catch (IOException e) {
            return Decoded.missing();
        }
        try {
            process.getOutputStream().close();
            if (!process.waitFor(limits.heifTimeoutSeconds(), TimeUnit.SECONDS)) {
                killTree(process);
                return Decoded.rejected(ReasonCode.RESOURCE_LIMIT_EXCEEDED);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            killTree(process);
            return Decoded.rejected(ReasonCode.UNREADABLE);
        }
        int exit = process.exitValue();
        if (exit == 126 || exit == 127) {
            return Decoded.missing();
        }
        if (exit != 0) {
            // Killed by the address-space limit shows as a failed allocation (non-zero) or a signal.
            return Decoded.rejected(exit > 128 ? ReasonCode.RESOURCE_LIMIT_EXCEEDED : ReasonCode.UNREADABLE);
        }
        // heif-dec writes the file as named for a single image; older tools add a "-1" suffix.
        Path suffixed = callDir.resolve("heic-decoded-1.jpg");
        if (Files.isRegularFile(out)) {
            return Decoded.ok(out);
        }
        if (Files.isRegularFile(suffixed)) {
            return Decoded.ok(suffixed);
        }
        return Decoded.rejected(ReasonCode.UNREADABLE);
    }

    private static void killTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * The largest width x height among the {@code ispe} (image spatial extents) boxes in the first
     * megabytes of the file: 4 bytes of version and flags, then a 32-bit width and height. The scan trusts
     * nothing else about the container.
     */
    static long largestDeclaredPixels(Path file) throws IOException {
        long largest = 0;
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(channel.size(), SCAN_BYTES));
            while (buffer.hasRemaining() && channel.read(buffer) > 0) {
                // fill
            }
            byte[] bytes = buffer.array();
            int length = buffer.position();
            for (int i = 0; i + ISPE.length + 12 <= length; i++) {
                if (bytes[i] == ISPE[0]
                        && bytes[i + 1] == ISPE[1]
                        && bytes[i + 2] == ISPE[2]
                        && bytes[i + 3] == ISPE[3]) {
                    long width = readUnsignedInt(bytes, i + 8);
                    long height = readUnsignedInt(bytes, i + 12);
                    long pixels;
                    try {
                        pixels = Math.multiplyExact(width, height);
                    } catch (ArithmeticException e) {
                        pixels = Long.MAX_VALUE;
                    }
                    largest = Math.max(largest, pixels);
                }
            }
        }
        return largest;
    }

    private static long readUnsignedInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xFFL) << 24)
                | ((bytes[offset + 1] & 0xFFL) << 16)
                | ((bytes[offset + 2] & 0xFFL) << 8)
                | (bytes[offset + 3] & 0xFFL);
    }
}
