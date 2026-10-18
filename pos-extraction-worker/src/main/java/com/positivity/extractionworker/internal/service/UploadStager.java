package com.positivity.extractionworker.internal.service;

import com.positivity.extractionworker.internal.exception.FileTooLargeException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import org.jspecify.annotations.NonNull;

/**
 * Streams an upload into the call directory, hashing it and counting bytes as it goes. This is all the web
 * process does with the bytes: it never parses structure. Stops and throws once the size limit is passed.
 */
final class UploadStager {

    /** What staging learned: the digest, the size, and the first bytes for the magic check. */
    record Staged(String sha256, long size, byte[] head) {}

    private UploadStager() {}

    static Staged stage(@NonNull InputStream in, @NonNull Path target, long maxBytes) throws IOException {
        MessageDigest digest = newDigest();
        byte[] head = new byte[MagicBytes.HEAD_BYTES];
        int headLength = 0;
        long size = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
                if (size > maxBytes) {
                    throw new FileTooLargeException(maxBytes);
                }
                if (headLength < head.length) {
                    int take = Math.min(read, head.length - headLength);
                    System.arraycopy(buffer, 0, head, headLength, take);
                    headLength += take;
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        return new Staged(HexFormat.of().formatHex(digest.digest()), size, Arrays.copyOf(head, headLength));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
