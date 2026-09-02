package com.positivity.referencemock.internal.domain;

import java.util.List;
import java.util.UUID;

/**
 * Root of the checked-in labor-guide fixture file
 * ({@code fixtures/laborguide/labor-guide-fixture.json}).
 *
 * @param sourceRevision the feed revision the whole fixture represents (e.g. {@code 2026-09-01})
 * @param importManifestId fixed manifest UUID stored in the fixture per revision, so the manifest
 *     endpoint is fully deterministic
 * @param operations the vendor's operation catalog (vendor codes {@code MG-<DURION-CODE>} plus at
 *     least one code with no Durion counterpart)
 * @param laborTimes the vehicle-keyed labor-time rows; {@code null} vehicle fields are wildcards
 */
public record LaborGuideFixture(
        String sourceRevision,
        UUID importManifestId,
        List<FixtureOperation> operations,
        List<FixtureLaborTime> laborTimes) {

    public LaborGuideFixture {
        operations = operations == null ? List.of() : List.copyOf(operations);
        laborTimes = laborTimes == null ? List.of() : List.copyOf(laborTimes);
    }
}
