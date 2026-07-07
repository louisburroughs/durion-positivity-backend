package com.positivity.inventory.internal.client;

import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Client for fetching the location roster from pos-location (source of
 * truth, ADR-0016). Consumes the paginated roster contract of CAP-214 #40.
 */
@Component
public class LocationRosterClient {

    private static final Logger log = LoggerFactory.getLogger(LocationRosterClient.class);

    private final RestClient restClient;
    private final int pageSize;

    public LocationRosterClient(
            @Qualifier("loadBalancedRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${pos.location.service-id:location}") String locationServiceId,
            @Value("${pos.location.roster.page-size:200}") int pageSize) {
        this.restClient =
                restClientBuilder.baseUrl("http://" + locationServiceId).build();
        this.pageSize = pageSize;
    }

    /**
     * Fetches the complete location roster, transparently walking every page.
     *
     * @throws LocationServiceUnavailableException when pos-location is unreachable or errors
     */
    @NonNull
    public List<LocationRosterEntry> fetchRoster() {
        List<LocationRosterEntry> roster = new ArrayList<>();
        int page = 0;
        RosterPage current;
        do {
            current = fetchPage(page);
            if (current.content() != null) {
                roster.addAll(current.content());
            }
            page++;
        } while (!current.last() && page < MAX_PAGES);
        if (page >= MAX_PAGES && !current.last()) {
            log.warn("Location roster paging stopped at safety cap of {} pages", MAX_PAGES);
        }
        return roster;
    }

    private RosterPage fetchPage(int page) {
        RosterPage rosterPage;
        try {
            rosterPage = restClient
                    .get()
                    .uri("/v1/locations/roster?page={page}&size={size}", page, pageSize)
                    .header("X-User", "pos-inventory")
                    .header("X-Authorities", "location:read")
                    .retrieve()
                    .body(RosterPage.class);
        } catch (RestClientException ex) {
            log.warn("pos-location roster fetch failed on page {}: {}", page, ex.getMessage());
            throw new LocationServiceUnavailableException("Location service unavailable while fetching roster", ex);
        }
        if (rosterPage == null) {
            throw new LocationServiceUnavailableException("Location roster returned an empty response", null);
        }
        return rosterPage;
    }

    private static final int MAX_PAGES = 1000;

    /**
     * Consumer-side projection of one pos-location roster record
     * (CAP-214 #40 contract): id, name, code, status, hrLocationId,
     * timezone, updatedAt.
     */
    public record LocationRosterEntry(
            UUID id,
            String name,
            String code,
            String status,
            String hrLocationId,
            String timezone,
            Instant updatedAt) {}

    /**
     * Minimal projection of Spring's page envelope returned by the roster
     * endpoint; only the fields needed to walk pages are mapped.
     */
    public record RosterPage(List<LocationRosterEntry> content, boolean last) {}
}
