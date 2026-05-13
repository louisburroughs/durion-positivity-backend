package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface BulkLoadBatchLauncher {

    void launch(@NonNull BulkLoadJob job, @Nullable String authorizationHeader);
}
