package com.positivity.referencemock.internal.service;

import com.positivity.referencemock.internal.domain.FixtureLaborTime;
import com.positivity.referencemock.internal.domain.LaborGuideFixture;
import com.positivity.referencemock.internal.dto.FeedChunkDto;
import com.positivity.referencemock.internal.dto.FeedLineDto;
import com.positivity.referencemock.internal.dto.FeedManifestDto;
import com.positivity.referencemock.internal.dto.ProviderLaborTimeDto;
import com.positivity.referencemock.internal.dto.ProviderOperationDto;
import com.positivity.referencemock.internal.dto.VehicleQuery;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.NonNull;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/**
 * Loads the checked-in labor-guide fixture once at startup and answers every contract endpoint
 * from it. The manifest (counts, checksum) is precomputed from the fixture rows so repeated calls
 * are byte-identical — determinism is the point of the mock (plan §10).
 */
@Service
public class LaborGuideFixtureService implements LaborGuideService {

    /** Fixed feed chunk size mandated by the provider contract. */
    static final int CHUNK_SIZE = 50;

    private static final String FIXTURE_PATH = "fixtures/laborguide/labor-guide-fixture.json";
    private static final List<String> TIME_TYPE_ORDER =
            List.of("RETAIL_FLAT_RATE", "OEM_WARRANTY", "MANUFACTURER_INSTALL");

    private final LaborGuideFixture fixture;
    private final FeedManifestDto manifest;
    private final List<FeedChunkDto> chunks;

    public LaborGuideFixtureService(@NonNull ObjectMapper objectMapper) {
        this.fixture = loadFixture(objectMapper);
        List<FeedLineDto> lines = fixture.laborTimes().stream()
                .map(LaborGuideFixtureService::toLine)
                .toList();
        this.manifest = new FeedManifestDto(
                fixture.importManifestId(),
                fixture.sourceRevision(),
                (lines.size() + CHUNK_SIZE - 1) / CHUNK_SIZE,
                lines.size(),
                checksum(lines));
        this.chunks = chunk(fixture.importManifestId(), lines);
    }

    @Override
    public @NonNull List<ProviderOperationDto> findOperations(@NonNull VehicleQuery vehicle, String search) {
        Set<String> applicableCodes = fixture.laborTimes().stream()
                .filter(row -> matchesWithRequestWildcards(row, vehicle))
                .map(FixtureLaborTime::providerOperationCode)
                .collect(Collectors.toSet());
        return fixture.operations().stream()
                .filter(op -> applicableCodes.contains(op.providerOperationCode()))
                .filter(op ->
                        search == null || op.name().toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
                .map(op -> new ProviderOperationDto(op.providerOperationCode(), op.name(), op.category()))
                .toList();
    }

    @Override
    public Optional<ProviderLaborTimeDto> findLaborTime(
            @NonNull String providerOperationCode, @NonNull VehicleQuery vehicle) {
        return fixture.laborTimes().stream()
                .filter(row -> row.providerOperationCode().equalsIgnoreCase(providerOperationCode))
                .filter(row -> matchesRequiringRequestFields(row, vehicle))
                .min(Comparator.comparingInt((FixtureLaborTime row) -> -row.specificity())
                        .thenComparingInt(row -> timeTypeRank(row.timeType())))
                .map(row -> new ProviderLaborTimeDto(
                        row.providerOperationCode(),
                        row.hours(),
                        row.timeType(),
                        row.includedOperations(),
                        row.overlapGroup(),
                        fixture.sourceRevision(),
                        row.publishedAt(),
                        row.notes()));
    }

    @Override
    public @NonNull FeedManifestDto manifest() {
        return manifest;
    }

    @Override
    public Optional<FeedChunkDto> chunk(int chunkSequence, @NonNull UUID manifestId) {
        if (!fixture.importManifestId().equals(manifestId) || chunkSequence < 1 || chunkSequence > chunks.size()) {
            return Optional.empty();
        }
        return Optional.of(chunks.get(chunkSequence - 1));
    }

    /**
     * Operations-endpoint matching: an absent request field is a wildcard, an absent row field is
     * a wildcard, and present values must match case-insensitively.
     */
    private static boolean matchesWithRequestWildcards(FixtureLaborTime row, VehicleQuery vehicle) {
        return fieldMatchesLoosely(row.vehicleYear(), vehicle.year())
                && fieldMatchesLoosely(row.make(), vehicle.make())
                && fieldMatchesLoosely(row.model(), vehicle.model())
                && fieldMatchesLoosely(row.submodel(), vehicle.submodel())
                && fieldMatchesLoosely(row.engineCode(), vehicle.engineCode());
    }

    /**
     * Labor-time matching: an absent row field is a wildcard, but a non-wildcard row field
     * requires the request to supply an equal value — a year-specific row must never answer a
     * request that named no year.
     */
    private static boolean matchesRequiringRequestFields(FixtureLaborTime row, VehicleQuery vehicle) {
        return fieldMatchesStrictly(row.vehicleYear(), vehicle.year())
                && fieldMatchesStrictly(row.make(), vehicle.make())
                && fieldMatchesStrictly(row.model(), vehicle.model())
                && fieldMatchesStrictly(row.submodel(), vehicle.submodel())
                && fieldMatchesStrictly(row.engineCode(), vehicle.engineCode());
    }

    private static boolean fieldMatchesLoosely(String rowValue, String requestValue) {
        return rowValue == null || requestValue == null || rowValue.equalsIgnoreCase(requestValue);
    }

    private static boolean fieldMatchesStrictly(String rowValue, String requestValue) {
        return rowValue == null || (requestValue != null && rowValue.equalsIgnoreCase(requestValue));
    }

    /** Equal-specificity ties resolve to the retail time first, deterministically. */
    private static int timeTypeRank(String timeType) {
        int rank = TIME_TYPE_ORDER.indexOf(timeType);
        return rank < 0 ? TIME_TYPE_ORDER.size() : rank;
    }

    private static FeedLineDto toLine(FixtureLaborTime row) {
        return new FeedLineDto(
                row.providerOperationCode(),
                row.vehicleYear(),
                row.make(),
                row.model(),
                row.submodel(),
                row.engineCode(),
                row.hours(),
                row.timeType(),
                row.overlapGroup(),
                row.includedOperations(),
                row.publishedAt());
    }

    private static List<FeedChunkDto> chunk(UUID manifestId, List<FeedLineDto> lines) {
        List<FeedChunkDto> result = new ArrayList<>();
        for (int start = 0; start < lines.size(); start += CHUNK_SIZE) {
            result.add(new FeedChunkDto(
                    manifestId, result.size() + 1, lines.subList(start, Math.min(start + CHUNK_SIZE, lines.size()))));
        }
        return List.copyOf(result);
    }

    /**
     * SHA-256 hex of the canonical concatenation of all lines: per line the fields
     * providerOperationCode, vehicleYear, make, model, submodel, engineCode, hours (plain string),
     * timeType, overlapGroup, includedOperations (comma-joined) and publishedAt (ISO date) joined
     * with {@code |}, nulls as empty strings, lines joined with {@code \n}. Consumers can verify
     * a completed import by recomputing this over the lines of all chunks in sequence order.
     */
    static String checksum(List<FeedLineDto> lines) {
        String canonical =
                lines.stream().map(LaborGuideFixtureService::canonicalLine).collect(Collectors.joining("\n"));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    static String canonicalLine(FeedLineDto line) {
        return String.join(
                "|",
                nullToEmpty(line.providerOperationCode()),
                nullToEmpty(line.vehicleYear()),
                nullToEmpty(line.make()),
                nullToEmpty(line.model()),
                nullToEmpty(line.submodel()),
                nullToEmpty(line.engineCode()),
                line.hours() == null ? "" : line.hours().toPlainString(),
                nullToEmpty(line.timeType()),
                nullToEmpty(line.overlapGroup()),
                String.join(",", line.includedOperations()),
                line.publishedAt() == null ? "" : line.publishedAt().toString());
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static LaborGuideFixture loadFixture(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(FIXTURE_PATH).getInputStream()) {
            return objectMapper.readValue(in, LaborGuideFixture.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load labor-guide fixture " + FIXTURE_PATH, e);
        }
    }
}
