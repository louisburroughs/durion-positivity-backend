package com.positivity.bulkloader.internal.config;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkloader.internal.domain.NumberedRecord;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Builds the {@link ItemWriter} that posts a domain's chunks to its owning service's
 * {@code /bulk-ingest} endpoint and records what happened to every row.
 *
 * <p>Each of the loader's domains needs the same six things — a load-balanced client, the operator
 * and authority headers, the caller's bearer token relayed on, the job's ids resolved off the job
 * parameters, the request envelope, and the per-row audit trail. Held as one factory, a new domain
 * is a call with five arguments rather than a fortieth copy of that sequence, and a fix to any of
 * it lands everywhere at once.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BulkIngestWriterFactory {

    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_USER = "X-User";
    private static final String BULK_LOADER_SERVICE_USER = "bulk-loader-service";

    private final AuthorizationHeaderRelay headerRelay;
    private final BulkIngestResultRecorder bulkIngestResultRecorder;

    /**
     * Where a domain's rows go and what it takes to be allowed to put them there.
     *
     * @param writerName the bean name, used in logs so a failing chunk names its own writer
     * @param domainType recorded on every audit row this writer produces
     * @param serviceId the Eureka service id of the owning service
     * @param uri the ingest path on that service, e.g. {@code /v1/catalog/bulk-ingest}
     * @param downstreamAuthority the permission the owning endpoint enforces, relayed as
     *     {@code X-Authorities}
     */
    public record Target(
            String writerName, DomainType domainType, String serviceId, String uri, String downstreamAuthority) {}

    /**
     * The Spring Batch job parameters a writer reads. Passed as one object because all four arrive
     * together from the same {@code @StepScope} injection and are meaningless apart.
     */
    public record JobParams(
            @Nullable String jobId,
            @Nullable String locationId,
            @Nullable String operatorId) {}

    /** A writer that posts each record as-is. */
    @NonNull
    public <I> ItemWriter<NumberedRecord<I>> create(
            RestClient.Builder restClientBuilder, @NonNull Target target, @NonNull JobParams params) {
        return create(restClientBuilder, target, params, items -> items);
    }

    /**
     * A writer that projects each record before posting it — for domains whose ingest DTO needs
     * typed values (a UUID, an int) that the loader record carries as text.
     */
    @NonNull
    public <I, P> ItemWriter<NumberedRecord<I>> create(
            RestClient.Builder restClientBuilder,
            @NonNull Target target,
            @NonNull JobParams params,
            @NonNull Function<List<I>, List<P>> payloadMapper) {

        RestClient client =
                restClientBuilder.baseUrl("http://" + target.serviceId()).build();

        return chunk -> {
            JobContext context = resolveJobContext(target.writerName(), params, chunk.size());
            if (context == null) {
                return;
            }
            String operatorId = sanitizeHeaderValue(params.operatorId(), BULK_LOADER_SERVICE_USER);

            // Row numbers come from the processor, which saw every row including the ones it
            // dropped; the writer only ever sees the survivors, so it cannot count them itself.
            List<Long> rowNumbers = new ArrayList<>(chunk.size());
            List<I> items = new ArrayList<>(chunk.size());
            for (NumberedRecord<I> numbered : chunk.getItems()) {
                rowNumbers.add(numbered.rowNumber());
                items.add(numbered.record());
            }
            postChunk(client, target, context, operatorId, rowNumbers, payloadMapper.apply(items));
        };
    }

    private <P> void postChunk(
            RestClient client,
            Target target,
            JobContext context,
            String operatorId,
            List<Long> rowNumbers,
            List<P> payloads) {

        BulkIngestRequest<P> request = new BulkIngestRequest<>();
        request.setJobId(context.jobId());
        request.setLocationId(context.locationId());
        request.setOperatorId(operatorId);
        request.setRecords(payloads);

        BulkIngestResponse response;
        try {
            RestClient.RequestBodySpec requestSpec = client.post()
                    .uri(target.uri())
                    .header(HEADER_AUTHORITIES, target.downstreamAuthority())
                    .header(HEADER_USER, operatorId);
            headerRelay.apply(requestSpec);
            response = requestSpec
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(BulkIngestResponse.class);
        } catch (RestClientException e) {
            log.error(
                    "{}: HTTP call failed for chunk of {} records: {}",
                    target.writerName(),
                    payloads.size(),
                    e.getMessage(),
                    e);
            throw e;
        }

        // A null response is recorded, not ignored: the rows were sent, and an operator needs to
        // see that nothing came back rather than read the silence as success.
        bulkIngestResultRecorder.record(context.jobId(), target.domainType(), rowNumbers, payloads, response);
    }

    private record JobContext(UUID jobId, UUID locationId) {}

    /**
     * The job's ids, or null when they are missing or unparseable — in which case the chunk is
     * skipped with a warning rather than posted somewhere it cannot be traced back to.
     */
    @Nullable
    private JobContext resolveJobContext(String writerName, JobParams params, int chunkSize) {
        if (params.jobId() == null || params.locationId() == null) {
            log.warn(
                    "{}: missing jobId or locationId job parameters, skipping chunk of {} records",
                    writerName,
                    chunkSize);
            return null;
        }
        try {
            return new JobContext(UUID.fromString(params.jobId()), UUID.fromString(params.locationId()));
        } catch (IllegalArgumentException _) {
            log.warn(
                    "{}: invalid jobId/locationId values (jobId={}, locationId={}), skipping chunk of {} records",
                    writerName,
                    params.jobId(),
                    params.locationId(),
                    chunkSize);
            return null;
        }
    }

    private String sanitizeHeaderValue(@Nullable String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String sanitizedValue = value.replaceAll("[\r\n\t]", "").trim();
        return sanitizedValue.isBlank() ? fallback : sanitizedValue;
    }
}
