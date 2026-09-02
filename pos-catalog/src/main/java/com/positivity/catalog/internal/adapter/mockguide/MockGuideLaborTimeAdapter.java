package com.positivity.catalog.internal.adapter.mockguide;

import com.positivity.catalog.internal.spi.LaborTimeProviderPort;
import com.positivity.catalog.internal.spi.ProviderCallException;
import com.positivity.catalog.internal.spi.model.LaborTimeProviderDescriptor;
import com.positivity.catalog.internal.spi.model.ProviderFeedChunk;
import com.positivity.catalog.internal.spi.model.ProviderFeedLine;
import com.positivity.catalog.internal.spi.model.ProviderFeedRevision;
import com.positivity.catalog.internal.spi.model.ProviderLaborTime;
import com.positivity.catalog.internal.spi.model.ProviderOperation;
import com.positivity.catalog.internal.spi.model.VehicleKey;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;

/**
 * Adapter for the {@code pos-reference-mock} labor-guide vendor (#1569 Phase 1, sourcing plan
 * §10). The mock speaks the Durion-normalized provider contract natively, so this adapter is a
 * thin HTTP binding — which is the point: it doubles as the permanent contract-test double for
 * the SPI, and a Phase-2 licensed-vendor adapter replaces it behind the same port with real
 * translation work.
 *
 * <p>One adapter class serves both licensing modes: the same instance is registered once as a
 * STORE source and once (different {@code sourceCode}) as QUERY_ONLY so both ingestion and the
 * live path are exercised from day one (sourcing plan §5.1).
 */
public class MockGuideLaborTimeAdapter implements LaborTimeProviderPort {

    /** Adapter key referenced by {@code pos.catalog.labor-guide.providers[].adapter}. */
    public static final String ADAPTER_KEY = "mockguide";

    private final LaborTimeProviderDescriptor descriptor;
    private final RestClient restClient;

    public MockGuideLaborTimeAdapter(LaborTimeProviderDescriptor descriptor, RestClient restClient) {
        this.descriptor = descriptor;
        this.restClient = restClient;
    }

    @Override
    @NonNull
    public LaborTimeProviderDescriptor descriptor() {
        return descriptor;
    }

    @Override
    @NonNull
    public List<ProviderOperation> findOperations(@NonNull VehicleKey vehicle, @Nullable String search) {
        try {
            List<OperationDto> body = restClient
                    .get()
                    .uri(builder -> vehicleParams(builder.path("/mock/labor-guide/v1/operations"), vehicle)
                            .queryParamIfPresent("search", Optional.ofNullable(search))
                            .build())
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return body == null
                    ? List.of()
                    : body.stream()
                            .map(op -> new ProviderOperation(op.providerOperationCode(), op.name(), op.category()))
                            .toList();
        } catch (RuntimeException e) {
            throw callFailure("operations", e);
        }
    }

    @Override
    @NonNull
    public Optional<ProviderLaborTime> getLaborTime(
            @NonNull VehicleKey vehicle, @NonNull String providerOperationCode) {
        try {
            LaborTimeDto body = restClient
                    .get()
                    .uri(builder -> vehicleParams(builder.path("/mock/labor-guide/v1/labor-times"), vehicle)
                            .queryParam("providerOperationCode", providerOperationCode)
                            .build())
                    .retrieve()
                    .body(LaborTimeDto.class);
            return Optional.ofNullable(body).map(MockGuideLaborTimeAdapter::toProviderLaborTime);
        } catch (HttpClientErrorException.NotFound e) {
            // The vendor answered: it has no time for this (vehicle, operation). A miss, not
            // a failure — the caller degrades to the next source, never to SOURCE_UNAVAILABLE.
            return Optional.empty();
        } catch (RuntimeException e) {
            throw callFailure("labor-times", e);
        }
    }

    @Override
    @NonNull
    public ProviderFeedRevision openFeedRevision(@Nullable String sinceRevision) {
        try {
            ManifestDto body = restClient
                    .get()
                    .uri(builder -> builder.path("/mock/labor-guide/v1/feed/manifest")
                            .queryParamIfPresent("sinceRevision", Optional.ofNullable(sinceRevision))
                            .build())
                    .retrieve()
                    .body(ManifestDto.class);
            if (body == null) {
                throw new ProviderCallException("[" + descriptor.sourceCode() + "] empty feed manifest");
            }
            return new ProviderFeedRevision(
                    body.importManifestId(),
                    body.sourceRevision(),
                    body.expectedChunkCount(),
                    body.expectedLineCount(),
                    body.contentChecksum());
        } catch (ProviderCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw callFailure("feed/manifest", e);
        }
    }

    @Override
    @NonNull
    public ProviderFeedChunk fetchFeedChunk(@NonNull UUID importManifestId, int chunkSequence) {
        try {
            ChunkDto body = restClient
                    .get()
                    .uri(builder -> builder.path("/mock/labor-guide/v1/feed/chunks/" + chunkSequence)
                            .queryParam("manifestId", importManifestId)
                            .build())
                    .retrieve()
                    .body(ChunkDto.class);
            if (body == null) {
                throw new ProviderCallException("[" + descriptor.sourceCode() + "] empty feed chunk " + chunkSequence);
            }
            List<ProviderFeedLine> lines = body.lines() == null
                    ? List.of()
                    : body.lines().stream()
                            .map(MockGuideLaborTimeAdapter::toFeedLine)
                            .toList();
            return new ProviderFeedChunk(body.importManifestId(), body.chunkSequence(), lines);
        } catch (ProviderCallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw callFailure("feed/chunks/" + chunkSequence, e);
        }
    }

    private static UriBuilder vehicleParams(UriBuilder builder, VehicleKey vehicle) {
        return builder.queryParamIfPresent("year", Optional.ofNullable(vehicle.vehicleYear()))
                .queryParamIfPresent("make", Optional.ofNullable(vehicle.make()))
                .queryParamIfPresent("model", Optional.ofNullable(vehicle.model()))
                .queryParamIfPresent("submodel", Optional.ofNullable(vehicle.submodel()))
                .queryParamIfPresent("engineCode", Optional.ofNullable(vehicle.engineCode()));
    }

    private static ProviderLaborTime toProviderLaborTime(LaborTimeDto dto) {
        return new ProviderLaborTime(
                dto.providerOperationCode(),
                dto.hours(),
                dto.timeType(),
                dto.includedOperations() == null ? List.of() : dto.includedOperations(),
                dto.overlapGroup(),
                dto.sourceRevision(),
                dto.publishedAt(),
                dto.notes());
    }

    private static ProviderFeedLine toFeedLine(FeedLineDto dto) {
        return new ProviderFeedLine(
                dto.providerOperationCode(),
                dto.vehicleYear(),
                dto.make(),
                dto.model(),
                dto.submodel(),
                dto.engineCode(),
                dto.hours(),
                dto.timeType(),
                dto.overlapGroup(),
                dto.includedOperations() == null ? List.of() : dto.includedOperations(),
                dto.publishedAt());
    }

    private ProviderCallException callFailure(String path, RuntimeException cause) {
        return new ProviderCallException("[" + descriptor.sourceCode() + "] labor-guide call failed: " + path, cause);
    }

    // ── Wire DTOs (the mock's contract v1; field names are normative) ────────────────────

    record OperationDto(String providerOperationCode, String name, String category) {}

    record LaborTimeDto(
            String providerOperationCode,
            BigDecimal hours,
            String timeType,
            List<String> includedOperations,
            String overlapGroup,
            String sourceRevision,
            LocalDate publishedAt,
            String notes) {}

    record ManifestDto(
            UUID importManifestId,
            String sourceRevision,
            int expectedChunkCount,
            long expectedLineCount,
            String contentChecksum) {}

    record ChunkDto(UUID importManifestId, int chunkSequence, List<FeedLineDto> lines) {}

    record FeedLineDto(
            String providerOperationCode,
            String vehicleYear,
            String make,
            String model,
            String submodel,
            String engineCode,
            BigDecimal hours,
            String timeType,
            String overlapGroup,
            List<String> includedOperations,
            LocalDate publishedAt) {}
}
