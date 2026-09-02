package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.enums.LaborTimeType;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import com.positivity.catalog.service.ServiceLaborTimeService;
import com.positivity.catalog.service.model.LaborTimeQuoteRequest;
import com.positivity.catalog.service.model.LaborTimeQuoteResponse;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Grant-surface implementation of {@link ServiceLaborTimeService} (#1569 Phase 1, ADR-0058 §5):
 * translates the contract records onto the internal resolution service and back. All actual
 * logic lives in {@link LaborTimeResolutionService}; this class exists so the granted interface
 * carries no {@code internal.*} types (ADR-0026 D4).
 *
 * <p>An unknown {@code preferredTimeType} string degrades to the default ordering rather than
 * erroring: the caller's flow must never fail over a preference hint.
 */
@Service
@RequiredArgsConstructor
public class ServiceLaborTimeServiceImpl implements ServiceLaborTimeService {

    private final LaborTimeResolutionService resolutionService;

    @Override
    @NonNull
    public LaborTimeQuoteResponse resolveLaborTime(@NonNull LaborTimeQuoteRequest request) {
        VehicleKey vehicle = new VehicleKey(
                trimToNull(request.vehicleYear()),
                trimToNull(request.make()),
                trimToNull(request.model()),
                trimToNull(request.submodel()),
                trimToNull(request.engineCode()));
        LaborTimeResolution resolution =
                resolutionService.resolve(request.serviceId(), vehicle, parsedPreference(request.preferredTimeType()));
        return new LaborTimeQuoteResponse(
                LaborTimeQuoteResponse.Status.valueOf(resolution.status().name()),
                resolution.laborHours(),
                resolution.timeType(),
                resolution.sourceCode(),
                resolution.sourceRevision(),
                resolution.matchGrade() == null
                        ? null
                        : LaborTimeQuoteResponse.MatchGrade.valueOf(
                                resolution.matchGrade().name()),
                resolution.overlapGroup(),
                resolution.includedOpCodes());
    }

    private static LaborTimeType parsedPreference(String preferredTimeType) {
        if (preferredTimeType == null || preferredTimeType.isBlank()) {
            return null;
        }
        try {
            return LaborTimeType.valueOf(preferredTimeType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
