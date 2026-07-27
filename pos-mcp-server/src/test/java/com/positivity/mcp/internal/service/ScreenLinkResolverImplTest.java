package com.positivity.mcp.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.domain.ScreenCandidate;
import com.positivity.mcp.internal.domain.ScreenLink;
import com.positivity.mcp.internal.repository.ScreenRegistryRepository;
import com.positivity.mcp.service.SiteMapService;
import com.positivity.mcp.service.SiteMapUnavailableException;
import com.positivity.mcp.service.model.SiteMap;
import com.positivity.mcp.service.model.SiteMapSection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;

@ExtendWith(MockitoExtension.class)
@DisplayName("ScreenLinkResolverImpl — semantic deep-link resolution (rung 3)")
class ScreenLinkResolverImplTest {

    private static final Set<String> VIEW_PERM = Set.of("workorder:workorder:view");
    private static final Set<String> NO_ROLES = Set.of();

    // Two orthogonal query/section vectors: identical → cosine 1.0, orthogonal → cosine 0.0.
    private final float[] vecA = {1f, 0f, 0f};
    private final float[] vecB = {0f, 1f, 0f};

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ScreenRegistryRepository repository;

    @Mock
    private SiteMapService siteMapService;

    private ScreenLinkResolverImpl resolver() {
        return new ScreenLinkResolverImpl(embeddingModel, repository, siteMapService, 0.6, 0.5);
    }

    private ScreenCandidate candidate(String key, String perm, double score) {
        return new ScreenCandidate(
                key, key, "/workorders?status={status}&location={locationId}", "workorder", perm, score);
    }

    private SiteMap siteMap(SiteMapSection... sections) {
        return new SiteMap("durion-positivity-frontend", 1, "2026-07-27T00:00:00.000Z", List.of(sections));
    }

    private SiteMapSection section(String route, String title, String description, List<String> roles) {
        return new SiteMapSection(
                route, "SHELL.NAV.KEY", title, "SITEMAP.SECTIONS.KEY.DESC", description, roles, "main", 1);
    }

    @Test
    @DisplayName("top candidate above floor returns a filled, permitted link")
    void topCandidateAboveFloorReturnsFilledUrl() {
        when(embeddingModel.embed("how many workorders are open")).thenReturn(vecA);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(candidate("workorders.list", "workorder:workorder:view", 0.82)));

        Optional<ScreenLink> link = resolver()
                .resolve("how many workorders are open", "workorder", VIEW_PERM, NO_ROLES, Map.of("status", "OPEN"));

        assertThat(link).isPresent();
        assertThat(link.get().screenKey()).isEqualTo("workorders.list");
        assertThat(link.get().url()).isEqualTo("/workorders?status=OPEN"); // unfilled locationId dropped
        assertThat(link.get().score()).isEqualTo(0.82);
        // Registry produced a permitted hit — the site-map fallback must not be consulted.
        verifyNoInteractions(siteMapService);
    }

    @Test
    @DisplayName("nothing above the similarity floor, no site-map match, returns empty")
    void belowFloorReturnsEmpty() {
        when(embeddingModel.embed("unrelated question")).thenReturn(vecA);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(candidate("workorders.list", null, 0.41)));
        when(siteMapService.getSiteMap())
                .thenReturn(siteMap(section("/app/crm", "Customers", "Manage customers.", null)));
        when(embeddingModel.embed(argThat((String text) -> text != null && text.contains("Customers"))))
                .thenReturn(vecB); // orthogonal to the query → cosine 0, below the floor

        assertThat(resolver().resolve("unrelated question", null, VIEW_PERM, NO_ROLES, Map.of()))
                .isEmpty();
    }

    @Test
    @DisplayName("permission-gated top candidate is skipped for the next permitted one")
    void permissionGatedTopSkippedNextReturned() {
        when(embeddingModel.embed("show me work")).thenReturn(vecA);
        when(repository.findNearest(any(), nullable(String.class), anyInt()))
                .thenReturn(List.of(
                        candidate("admin.secret", "admin:secret:view", 0.90),
                        candidate("workorders.list", null, 0.71)));

        Optional<ScreenLink> link = resolver().resolve("show me work", "workorder", VIEW_PERM, NO_ROLES, Map.of());

        assertThat(link).isPresent();
        assertThat(link.get().screenKey()).isEqualTo("workorders.list");
        verifyNoInteractions(siteMapService);
    }

    @Test
    @DisplayName("blank message returns empty without embedding or site-map lookup")
    void blankMessageReturnsEmptyWithoutEmbedding() {
        assertThat(resolver().resolve("   ", null, VIEW_PERM, NO_ROLES, Map.of()))
                .isEmpty();
        verifyNoInteractions(embeddingModel, repository, siteMapService);
    }

    @Test
    @DisplayName("site-map fallback returns a routed link when the registry finds nothing")
    void siteMapFallbackReturnsRoutedLink() {
        when(embeddingModel.embed("where can I manage customers")).thenReturn(vecA);
        when(repository.findNearest(any(), nullable(String.class), anyInt())).thenReturn(List.of());
        when(siteMapService.getSiteMap())
                .thenReturn(siteMap(
                        section("/app/crm", "Customers", "Manage customers, contacts, and relationships.", null)));
        when(embeddingModel.embed(argThat((String text) -> text != null && text.contains("Customers"))))
                .thenReturn(vecA); // aligned with the query → cosine 1.0, clears the floor

        Optional<ScreenLink> link =
                resolver().resolve("where can I manage customers", null, VIEW_PERM, NO_ROLES, Map.of());

        assertThat(link).isPresent();
        assertThat(link.get().screenKey()).isEqualTo("/app/crm");
        assertThat(link.get().url()).isEqualTo("/app/crm");
        assertThat(link.get().title()).isEqualTo("Customers");
    }

    @Test
    @DisplayName("site-map section is role-gated: hidden without the role, shown with it")
    void siteMapSectionRoleGated() {
        when(embeddingModel.embed("open administration configuration")).thenReturn(vecB);
        when(repository.findNearest(any(), nullable(String.class), anyInt())).thenReturn(List.of());
        when(siteMapService.getSiteMap())
                .thenReturn(siteMap(section(
                        "/app/admin",
                        "Administration",
                        "System administration and configuration.",
                        List.of("ROLE_ADMIN"))));
        when(embeddingModel.embed(argThat((String text) -> text != null && text.contains("Administration"))))
                .thenReturn(vecB); // aligned with the query → cosine 1.0

        assertThat(resolver().resolve("open administration configuration", null, VIEW_PERM, NO_ROLES, Map.of()))
                .isEmpty();
        assertThat(resolver()
                        .resolve("open administration configuration", null, VIEW_PERM, Set.of("ROLE_ADMIN"), Map.of()))
                .isPresent();
    }

    @Test
    @DisplayName("a site-map outage degrades the fallback to empty, never throwing")
    void siteMapOutageDegradesToEmpty() {
        when(embeddingModel.embed("manage customers")).thenReturn(vecA);
        when(repository.findNearest(any(), nullable(String.class), anyInt())).thenReturn(List.of());
        when(siteMapService.getSiteMap()).thenThrow(new SiteMapUnavailableException("frontend unreachable"));

        assertThat(resolver().resolve("manage customers", null, VIEW_PERM, NO_ROLES, Map.of()))
                .isEmpty();
    }
}
