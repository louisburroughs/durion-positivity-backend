package com.positivity.mcp.internal.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.positivity.mcp.internal.config.SiteMapProperties;
import com.positivity.mcp.service.SiteMapUnavailableException;
import com.positivity.mcp.service.model.SiteMap;
import com.positivity.mcp.service.model.SiteMapSection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Unit tests for {@link SiteMapClient}, covering the reference cases from the frontend's
 * {@code docs/sitemap/client-module.md} consumer spec against the canonical example payload.
 */
class SiteMapClientTest {

    private static final String BASE_URL = "http://frontend";
    private static final String SITEMAP_URL = BASE_URL + "/sitemap.json";
    private static final Instant T0 = Instant.parse("2026-07-27T00:00:00Z");

    // Canonical fixture from docs/sitemap/client-module.md — main section (no roles) before admin.
    private static final String CANONICAL_PAYLOAD = """
            {
              "application": "durion-positivity-frontend",
              "version": 1,
              "generatedAt": "2026-07-27T00:00:00.000Z",
              "sections": [
                {
                  "route": "/app/crm",
                  "titleKey": "SHELL.NAV.CRM",
                  "title": "Customers",
                  "descriptionKey": "SITEMAP.SECTIONS.CRM.DESC",
                  "description": "Manage customers, contacts, and relationships.",
                  "group": "main",
                  "order": 3
                },
                {
                  "route": "/app/admin",
                  "titleKey": "SHELL.NAV.ADMIN",
                  "title": "Admin",
                  "descriptionKey": "SITEMAP.SECTIONS.ADMIN.DESC",
                  "description": "System administration and configuration.",
                  "roles": ["ROLE_ADMIN"],
                  "group": "admin",
                  "order": 12
                }
              ]
            }
            """;

    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(T0);
    }

    @Test
    @DisplayName("case 1: happy path returns parsed site map in (group, order) order")
    void happyPath_returnsParsedSiteMapInOrder() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));

        SiteMap siteMap = fixture.client().getSiteMap();

        fixture.server().verify();
        assertThat(siteMap.application()).isEqualTo("durion-positivity-frontend");
        assertThat(siteMap.version()).isEqualTo(1);
        assertThat(siteMap.generatedAt()).isEqualTo("2026-07-27T00:00:00.000Z");
        assertThat(siteMap.sections()).extracting(SiteMapSection::route).containsExactly("/app/crm", "/app/admin");
    }

    @Test
    @DisplayName("case 2: visibleSections([]) returns only sections without a roles restriction")
    void visibleSections_noRoles_returnsOnlyUnrestricted() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));
        fixture.client().getSiteMap();

        List<SiteMapSection> visible = fixture.client().visibleSections(List.of());

        assertThat(visible).extracting(SiteMapSection::route).containsExactly("/app/crm");
    }

    @Test
    @DisplayName("case 3: visibleSections([ROLE_ADMIN]) includes admin-gated sections")
    void visibleSections_admin_includesAdminSection() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));
        fixture.client().getSiteMap();

        List<SiteMapSection> visible = fixture.client().visibleSections(List.of("ROLE_ADMIN"));

        assertThat(visible).extracting(SiteMapSection::route).containsExactly("/app/crm", "/app/admin");
    }

    @Test
    @DisplayName("case 4: section role match is 'any of' the listed roles")
    void visibleSections_roleMatchIsAnyOf() {
        String payload = """
                {
                  "application": "durion-positivity-frontend",
                  "version": 1,
                  "generatedAt": "2026-07-27T00:00:00.000Z",
                  "sections": [
                    {
                      "route": "/app/multi",
                      "titleKey": "SHELL.NAV.MULTI",
                      "title": "Multi",
                      "descriptionKey": "SITEMAP.SECTIONS.MULTI.DESC",
                      "description": "Requires either role.",
                      "roles": ["ROLE_A", "ROLE_B"],
                      "group": "main",
                      "order": 1
                    }
                  ]
                }
                """;
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));
        fixture.client().getSiteMap();

        assertThat(fixture.client().visibleSections(List.of("ROLE_B")))
                .extracting(SiteMapSection::route)
                .containsExactly("/app/multi");
        assertThat(fixture.client().visibleSections(List.of("ROLE_C"))).isEmpty();
    }

    @Test
    @DisplayName("case 5: HTTP 500 with a warm cache returns last-known-good and does not throw")
    void fetchFailure_warmCache_returnsLastKnownGood() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));
        expectSitemap(fixture.server()).andRespond(withServerError());

        SiteMap first = fixture.client().getSiteMap();
        clock.advance(Duration.ofSeconds(601)); // beyond the 600s TTL → next access refetches
        SiteMap second = fixture.client().getSiteMap();

        fixture.server().verify();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("case 6: HTTP failure with a cold cache surfaces SiteMapUnavailableException")
    void fetchFailure_coldCache_throwsUnavailable() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withServerError());

        assertThatThrownBy(() -> fixture.client().getSiteMap()).isInstanceOf(SiteMapUnavailableException.class);
        fixture.server().verify();
    }

    @Test
    @DisplayName("case 7a: malformed JSON body is a fetch failure (cold cache → unavailable)")
    void malformedBody_coldCache_throwsUnavailable() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess("{not-json", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().getSiteMap()).isInstanceOf(SiteMapUnavailableException.class);
    }

    @Test
    @DisplayName("case 7b: non-JSON content type is a fetch failure")
    void nonJsonContentType_throwsUnavailable() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server())
                .andRespond(withStatus(org.springframework.http.HttpStatus.OK)
                        .body("<html></html>")
                        .contentType(MediaType.TEXT_HTML));

        assertThatThrownBy(() -> fixture.client().getSiteMap()).isInstanceOf(SiteMapUnavailableException.class);
    }

    @Test
    @DisplayName("case 8: wrong application value is rejected as a fetch failure")
    void wrongApplication_throwsUnavailable() {
        String payload = """
                {
                  "application": "some-other-app",
                  "version": 1,
                  "generatedAt": "2026-07-27T00:00:00.000Z",
                  "sections": []
                }
                """;
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> fixture.client().getSiteMap()).isInstanceOf(SiteMapUnavailableException.class);
    }

    @Test
    @DisplayName("cases 9 & 12: newer version and unknown fields are tolerated, not fatal")
    void newerVersionAndUnknownFields_areTolerated() {
        String payload = """
                {
                  "application": "durion-positivity-frontend",
                  "version": 2,
                  "generatedAt": "2026-07-27T00:00:00.000Z",
                  "futureTopLevelField": {"anything": true},
                  "sections": [
                    {
                      "route": "/app/crm",
                      "titleKey": "SHELL.NAV.CRM",
                      "title": "Customers",
                      "descriptionKey": "SITEMAP.SECTIONS.CRM.DESC",
                      "description": "Manage customers.",
                      "group": "main",
                      "order": 3,
                      "futureSectionField": "ignored"
                    }
                  ]
                }
                """;
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(payload, MediaType.APPLICATION_JSON));

        SiteMap siteMap = fixture.client().getSiteMap();

        assertThat(siteMap.version()).isEqualTo(2);
        assertThat(siteMap.sections()).extracting(SiteMapSection::route).containsExactly("/app/crm");
    }

    @Test
    @DisplayName("case 10: within TTL a second call is served from cache without a second HTTP request")
    void withinTtl_secondCallUsesCache() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));

        SiteMap first = fixture.client().getSiteMap();
        SiteMap second = fixture.client().getSiteMap(); // no clock advance → still fresh

        fixture.server().verify(); // exactly one request was expected
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("case 11: refresh() performs an HTTP request even within TTL")
    void refresh_forcesFetchWithinTtl() {
        Fixture fixture = fixture(clock);
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));
        expectSitemap(fixture.server()).andRespond(withSuccess(CANONICAL_PAYLOAD, MediaType.APPLICATION_JSON));

        fixture.client().getSiteMap();
        fixture.client().refresh(); // within TTL, but must still hit the server

        fixture.server().verify(); // two requests expected
    }

    private static org.springframework.test.web.client.ResponseActions expectSitemap(MockRestServiceServer server) {
        return server.expect(requestTo(SITEMAP_URL)).andExpect(method(HttpMethod.GET));
    }

    private static Fixture fixture(Clock clock) {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        RestClient restClient = builder.build();
        SiteMapProperties properties =
                new SiteMapProperties(BASE_URL, Duration.ofSeconds(600), Duration.ofSeconds(5), 1);
        SiteMapClient client = new SiteMapClient(restClient, properties, clock);
        return new Fixture(client, server);
    }

    private record Fixture(SiteMapClient client, MockRestServiceServer server) {}

    /** A hand-advanced {@link Clock} so TTL expiry can be driven deterministically in tests. */
    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant start) {
            this.instant = start;
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public Instant instant() {
            return instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
