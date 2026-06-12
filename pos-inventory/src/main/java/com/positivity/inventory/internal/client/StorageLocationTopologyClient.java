package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.exception.LocationNotFoundException;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/**
 * Client for fetching the storage-location topology of a site from
 * pos-location (source of truth, ADR-0016). Consumes the unpaginated
 * topology contract introduced by CAP-214 #655.
 */
@Component
public class StorageLocationTopologyClient {

    private static final Logger log = LoggerFactory.getLogger(StorageLocationTopologyClient.class);

    private final RestClient restClient;

    public StorageLocationTopologyClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.service-id:location}") String locationServiceId) {
        this.restClient =
                restClientBuilder.baseUrl("http://" + locationServiceId).build();
    }

    /**
     * Fetches the complete flat storage-location topology for a site.
     *
     * @throws LocationNotFoundException when pos-location reports the site does not exist
     * @throws LocationServiceUnavailableException when pos-location is unreachable or errors
     */
    @NonNull
    public List<StorageLocationNode> fetchSiteTopology(@NonNull UUID siteId) {
        try {
            List<StorageLocationNode> nodes = restClient
                    .get()
                    .uri("/v1/locations/{siteId}/storage-locations/topology", siteId)
                    .header("X-User", "pos-inventory")
                    .header("X-Authorities", "location:read")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return nodes == null ? List.of() : nodes;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new LocationNotFoundException(siteId);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            log.warn("pos-location topology fetch failed for site {}: {}", siteId, ex.getMessage());
            throw new LocationServiceUnavailableException(
                    "Location service unavailable while fetching site topology", ex);
        }
    }

    /**
     * Fetches the flat list of descendant locations of a parent location
     * along the given typed parent chain (CAP-214 #655 descendants contract).
     *
     * @param parentType pos-location {@code ParentType} name, e.g. {@code PHYSICAL}
     * @throws LocationNotFoundException when pos-location reports the location does not exist
     * @throws LocationServiceUnavailableException when pos-location is unreachable or errors
     */
    @NonNull
    public List<LocationDescendant> fetchDescendants(@NonNull UUID locationId, @NonNull String parentType) {
        try {
            List<LocationDescendant> descendants = restClient
                    .get()
                    .uri("/v1/locations/{locationId}/descendants?parentType={parentType}", locationId, parentType)
                    .header("X-User", "pos-inventory")
                    .header("X-Authorities", "location:read")
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            return descendants == null ? List.of() : descendants;
        } catch (HttpClientErrorException.NotFound ex) {
            throw new LocationNotFoundException(locationId);
        } catch (HttpServerErrorException | ResourceAccessException ex) {
            log.warn("pos-location descendants fetch failed for location {}: {}", locationId, ex.getMessage());
            throw new LocationServiceUnavailableException(
                    "Location service unavailable while fetching descendants", ex);
        }
    }

    /**
     * Consumer-side projection of the pos-location topology contract.
     * Fields pinned by the CAP-214 #655 contract tests:
     * id, name, type, status, parentStorageLocationId.
     */
    public record StorageLocationNode(UUID id, String name, String type, String status, UUID parentStorageLocationId) {}

    /**
     * Consumer-side projection of the pos-location descendants contract
     * (CAP-214 #655): id, name, code, status, parentId, depth.
     */
    public record LocationDescendant(UUID id, String name, String code, String status, UUID parentId, int depth) {}
}
