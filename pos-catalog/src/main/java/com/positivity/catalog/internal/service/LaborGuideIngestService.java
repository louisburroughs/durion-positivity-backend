package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.LaborGuideImportSummaryDto;
import com.positivity.catalog.internal.dto.LaborGuideUnmappedOperationDto;
import java.util.List;
import org.jspecify.annotations.NonNull;

/**
 * Chunked-manifest import of a STORE-licensed labor-guide feed into
 * {@code service_labor_standard} (#1569 Phase 1, sourcing plan §5.3; ADR-0053 shape adapted
 * from Kafka-consumer to adapter-pull).
 */
public interface LaborGuideIngestService {

    /**
     * Runs (or resumes) an import for the named source: opens the provider's current feed
     * revision, applies every not-yet-applied chunk idempotently, and closes the import with a
     * counted completeness verdict. A revision already imported COMPLETE is a cheap no-op.
     *
     * <p>Line semantics per sourcing plan §5.3: unmapped vendor codes land in the curation
     * queue and the import continues; an unchanged line is a skip; a changed line supersedes
     * the active row and inserts the replacement.
     */
    @NonNull
    LaborGuideImportSummaryDto runImport(@NonNull String sourceCode);

    /** Imports whose chunks or line counts have not reconciled (APPLYING or INCOMPLETE), newest first. */
    @NonNull
    List<LaborGuideImportSummaryDto> listIncompleteImports();

    /** The unmapped-operation curation queue, most recently seen first. */
    @NonNull
    List<LaborGuideUnmappedOperationDto> listUnmappedOperations();
}
