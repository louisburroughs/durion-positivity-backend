package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.dto.ServiceLaborStandardImportRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardRequestDto;
import com.positivity.catalog.internal.dto.ServiceLaborStandardResponseDto;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Authoring surface for hand-authored (DURION-source) labor standards (#1569, sourcing plan
 * §4.4). Imported rows will arrive through the labor-guide ingest path in a later phase and are
 * not writable here.
 */
public interface ServiceLaborStandardService {

    /**
     * Applies one imported standard, addressed by its service's operation code and carrying the
     * source and revision that published it.
     *
     * <p>Line semantics match the labor-guide import (sourcing plan §5.3), because this is the
     * same act arriving by a different transport: a row already active on the same vehicle key
     * under the same source and revision is a skip, anything else supersedes the active row and
     * inserts the replacement, and a key nothing holds is an insert. Re-running a fixture pack is
     * therefore a no-op rather than a wall of duplicate-key rejections, which is what
     * docs/DATA_SEED_STRATEGY.md §5.3 asks of every seed-target endpoint.
     *
     * <p>Separate from {@link #create} because it can write any source's provenance, which
     * authoring deliberately cannot: {@code create} stamps DURION so a shop cannot publish a
     * number under a vendor's name. That is a wider authority and carries the import permission
     * rather than the manage one.
     */
    @NonNull
    ServiceLaborStandardResponseDto importStandard(
            @NonNull String operationCode, @NonNull ServiceLaborStandardImportRequestDto request);

    /** Creates an active DURION-source standard for the service. */
    @NonNull
    ServiceLaborStandardResponseDto create(@NonNull UUID serviceId, @NonNull ServiceLaborStandardRequestDto request);

    /** Lists a service's standards, active only unless {@code includeSuperseded}. */
    @NonNull
    List<ServiceLaborStandardResponseDto> list(@NonNull UUID serviceId, boolean includeSuperseded);

    /**
     * Replaces an active DURION-source row: marks it superseded and inserts the replacement in
     * the same transaction, returning the replacement. The old row stays readable for audit.
     */
    @NonNull
    ServiceLaborStandardResponseDto supersede(
            @NonNull UUID serviceId, @NonNull UUID standardId, @NonNull ServiceLaborStandardRequestDto request);
}
