package com.positivity.bulkloader.service;

import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.enums.DomainType;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;

public interface ContentDetectionService {

    ContentDetectionResult detect(@NonNull List<String> columnHeaders, @NonNull List<List<String>> sampleRows);

    /**
     * Suggests source-column-to-target-field mappings for the given headers and domain. Headers
     * that cannot be inferred are omitted; each target field is claimed by at most one header
     * (first match wins).
     */
    @NonNull
    Map<String, String> suggestMappings(@NonNull List<String> columnHeaders, @NonNull DomainType domainType);
}
