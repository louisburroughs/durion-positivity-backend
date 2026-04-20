package com.positivity.bulkloader.service;

import java.io.InputStream;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

public interface FileStorageService {

    String store(@NonNull UUID jobId, @NonNull String fileName, @NonNull InputStream content, long sizeBytes);

    InputStream retrieve(@NonNull String storagePath);

    void delete(@NonNull String storagePath);
}
