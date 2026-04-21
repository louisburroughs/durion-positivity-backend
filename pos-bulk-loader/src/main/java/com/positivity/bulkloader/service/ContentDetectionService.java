package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import java.util.List;
import org.jspecify.annotations.NonNull;

public interface ContentDetectionService {

    ContentDetectionResult detect(@NonNull List<String> columnHeaders, @NonNull List<List<String>> sampleRows);
}
