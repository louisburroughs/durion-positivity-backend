package com.positivity.bulkloader.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface TusUploadService {

    record Created(@NonNull UUID id, @NonNull Instant expiresAt) {}

    record Info(long uploadOffset, long totalSize, @NonNull Instant expiresAt, boolean completed) {}

    Created createUpload(@NonNull UUID jobId, @NonNull String fileName, long totalSize, @NonNull String operatorId);

    Info getInfo(@NonNull UUID uploadId);

    long appendChunk(@NonNull UUID uploadId, long expectedOffset, @NonNull InputStream data, long chunkLength)
            throws IOException;

    void deleteUpload(@NonNull UUID uploadId);
}
