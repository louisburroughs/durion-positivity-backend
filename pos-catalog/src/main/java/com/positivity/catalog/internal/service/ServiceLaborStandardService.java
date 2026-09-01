package com.positivity.catalog.internal.service;

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
