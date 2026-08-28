package com.positivity.bulkloader.internal.config;

import com.positivity.bulkingest.BulkIngestRequest;
import com.positivity.bulkingest.BulkIngestResponse;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.service.BulkIngestResultRecorder;
import com.positivity.bulkloader.internal.service.BulkLoadAuthorizationContext;
import com.positivity.security.common.GatewaySecurityConstants;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_USER = "X-User";
    private static final String BULK_LOADER_SERVICE_USER = "bulk-loader-service";

    private final BulkLoadAuthorizationContext bulkLoadAuthorizationContext;
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
    public <I> ItemWriter<I> create(
            RestClient.Builder restClientBuilder, @NonNull Target target, @NonNull JobParams params) {
        return create(restClientBuilder, target, params, items -> items);
    }

    /**
     * A writer that projects each record before posting it — for domains whose ingest DTO needs
     * typed values (a UUID, an int) that the loader record carries as text.
     */
    @NonNull
    public <I, P> ItemWriter<I> create(
            RestClient.Builder restClientBuilder,
            @NonNull Target target,
            @NonNull JobParams params,
            @NonNull Function<List<I>, List<P>> payloadMapper) {

        RestClient client =
                restClientBuilder.baseUrl("http://" + target.serviceId()).build();
        // Per step instance, so an audit row's number reads against the uploaded file rather than
        // restarting at zero for every chunk.
        AtomicLong rowCursor = new AtomicLong();

        return chunk -> {
            JobContext context = resolveJobContext(target.writerName(), params, chunk.size());
            if (context == null) {
                return;
            }
            String operatorId = sanitizeHeaderValue(params.operatorId(), BULK_LOADER_SERVICE_USER);
            // Copied out of the chunk so the mapper — and the audit trail built from its result —
            // work on a list that outlives Spring Batch's wildcard-typed view of it.
            List<I> items = new ArrayList<>(chunk.getItems());
            postChunk(client, target, context, operatorId, payloadMapper.apply(items), rowCursor);
        };
    }

    private <P> void postChunk(
            RestClient client,
            Target target,
            JobContext context,
            String operatorId,
            List<P> payloads,
            AtomicLong rowCursor) {

        BulkIngestRequest<P> request = new BulkIngestRequest<>();
        request.setJobId(context.jobId());
        request.setLocationId(context.locationId());
        request.setOperatorId(operatorId);
        request.setRecords(payloads);

        long rowOffset = rowCursor.getAndAdd(payloads.size());

        BulkIngestResponse response;
        try {
            RestClient.RequestBodySpec requestSpec = client.post()
                    .uri(target.uri())
                    .header(HEADER_AUTHORITIES, target.downstreamAuthority())
                    .header(HEADER_USER, operatorId);
            applyRelayHeaders(requestSpec);
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
        bulkIngestResultRecorder.record(context.jobId(), target.domainType(), rowOffset, payloads, response);
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

    private void applyRelayHeaders(RestClient.RequestBodySpec requestSpec) {
        String authorizationHeader = resolveAuthorizationHeader();
        if (!StringUtils.hasText(authorizationHeader)) {
            return;
        }
        requestSpec.header(HttpHeaders.AUTHORIZATION, authorizationHeader);
        requestSpec.header(GatewaySecurityConstants.HEADER_TOKEN, extractTokenValue(authorizationHeader));
    }

    /**
     * The caller's bearer token: from the launch-time context first (the batch thread has no
     * request bound to it), then from the current request, then from the gateway token header.
     */
    @Nullable
    private String resolveAuthorizationHeader() {
        String launchAuthorizationHeader = bulkLoadAuthorizationContext.getAuthorizationHeader();
        if (StringUtils.hasText(launchAuthorizationHeader) && launchAuthorizationHeader.startsWith(BEARER_PREFIX)) {
            return launchAuthorizationHeader;
        }

        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes requestAttributes)) {
            return null;
        }

        HttpServletRequest request = requestAttributes.getRequest();
        String authorizationHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (StringUtils.hasText(authorizationHeader) && authorizationHeader.startsWith(BEARER_PREFIX)) {
            return authorizationHeader;
        }

        String gatewayTokenHeader = request.getHeader(GatewaySecurityConstants.HEADER_TOKEN);
        if (!StringUtils.hasText(gatewayTokenHeader)) {
            return null;
        }
        return gatewayTokenHeader.startsWith(BEARER_PREFIX) ? gatewayTokenHeader : BEARER_PREFIX + gatewayTokenHeader;
    }

    private String extractTokenValue(String authorizationHeader) {
        return authorizationHeader.startsWith(BEARER_PREFIX)
                ? authorizationHeader.substring(BEARER_PREFIX.length())
                : authorizationHeader;
    }
}
