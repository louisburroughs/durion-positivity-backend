package com.positivity.accounting.internal.billintake.service;

import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The extraction port of profiles without a worker (dev, tests, and every deployment until S26's adapter lands): every
 * operation answers {@code UNAVAILABLE}, so an upload stays quarantined (ruling 2). Selected by {@code
 * pos.accounting.bill-intake.extraction.adapter=unavailable}, the default.
 */
@Component
@ConditionalOnProperty(
        name = "pos.accounting.bill-intake.extraction.adapter",
        havingValue = "unavailable",
        matchIfMissing = true)
public class UnavailableExtractionPort implements ExtractionPort {

    @Override
    public @NonNull InspectionResult inspect(byte @NonNull [] file, @NonNull DeclaredType declaredType) {
        return InspectionResult.unavailable();
    }

    @Override
    public @NonNull RenderResult render(byte @NonNull [] file, @NonNull DeclaredType declaredType, int page) {
        return RenderResult.unavailable();
    }

    @Override
    public @NonNull TabulationResult tabulate(byte @NonNull [] file, @NonNull DeclaredType declaredType) {
        return TabulationResult.unavailable();
    }

    @Override
    public @NonNull ExtractionResult extract(
            byte @NonNull [] file,
            @NonNull DeclaredType declaredType,
            @NonNull DocumentKind documentKind,
            @NonNull String localeHint) {
        return ExtractionResult.unavailable();
    }
}
