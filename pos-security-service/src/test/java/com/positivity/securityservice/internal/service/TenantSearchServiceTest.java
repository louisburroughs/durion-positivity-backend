package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.config.TenantSearchProperties;
import com.positivity.securityservice.internal.dto.TenantSearchResponse;
import com.positivity.securityservice.internal.entity.ExtTenant;
import com.positivity.securityservice.internal.repository.ExtTenantRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/**
 * The bounds on the public organization directory (ADR-0062 §3): what a caller may learn per query,
 * and that the query itself cannot widen them.
 */
class TenantSearchServiceTest {

    private final ExtTenantRepository tenants = mock(ExtTenantRepository.class);

    private TenantSearchService service(boolean enabled, int minLength, int maxResults) {
        return new TenantSearchService(tenants, new TenantSearchProperties(enabled, minLength, maxResults));
    }

    private TenantSearchService service() {
        return service(true, 3, 10);
    }

    private static ExtTenant tenant(String slug, String displayName) {
        return ExtTenant.builder()
                .tenantId(UUID.randomUUID())
                .slug(slug)
                .displayName(displayName)
                .status("ACTIVE")
                .build();
    }

    @Test
    @DisplayName("a query shorter than the minimum is not searched at all")
    void shortQueryNeverReachesTheDatabase() {
        assertThat(service().search("ac")).isEmpty();
        assertThat(service().search("")).isEmpty();
        assertThat(service().search("   ")).isEmpty();
        assertThat(service().search(null)).isEmpty();
        verifyNoInteractions(tenants);
    }

    @Test
    @DisplayName("the query is normalized the same way the stored key was")
    void queryIsNormalized() {
        when(tenants.searchByDisplayNamePrefix(any(), any(), any())).thenReturn(List.of());

        service().search("  ACME   Tire ");

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(tenants).searchByDisplayNamePrefix(prefix.capture(), eq("ACTIVE"), any());
        assertThat(prefix.getValue()).isEqualTo("acme tire");
    }

    @Test
    @DisplayName("LIKE wildcards in the query are escaped, so '%' cannot dump the directory")
    void wildcardsAreEscaped() {
        assertThat(TenantSearchService.escapeLike("%")).isEqualTo("\\%");
        assertThat(TenantSearchService.escapeLike("a_b")).isEqualTo("a\\_b");
        assertThat(TenantSearchService.escapeLike("100%_x")).isEqualTo("100\\%\\_x");
        // The escape character itself is escaped first, so it cannot smuggle a wildcard through.
        assertThat(TenantSearchService.escapeLike("\\%")).isEqualTo("\\\\\\%");
    }

    @Test
    @DisplayName("a wildcard query is passed through escaped, not raw")
    void wildcardQueryIsSearchedLiterally() {
        when(tenants.searchByDisplayNamePrefix(any(), any(), any())).thenReturn(List.of());

        service().search("%%%");

        ArgumentCaptor<String> prefix = ArgumentCaptor.forClass(String.class);
        verify(tenants).searchByDisplayNamePrefix(prefix.capture(), any(), any());
        assertThat(prefix.getValue()).isEqualTo("\\%\\%\\%");
    }

    @Test
    @DisplayName("the result cap is what the repository is asked for, not trimmed afterwards")
    void capIsPushedIntoTheQuery() {
        when(tenants.searchByDisplayNamePrefix(any(), any(), any())).thenReturn(List.of());

        service(true, 3, 10).search("acme");

        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(tenants).searchByDisplayNamePrefix(any(), any(), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(10);
        assertThat(page.getValue().getPageNumber()).isZero();
    }

    @Test
    @DisplayName("only ACTIVE tenants are asked for")
    void onlyActiveTenantsAreOffered() {
        when(tenants.searchByDisplayNamePrefix(any(), any(), any())).thenReturn(List.of());

        service().search("acme");

        verify(tenants).searchByDisplayNamePrefix(any(), eq("ACTIVE"), any());
    }

    @Test
    @DisplayName("the response carries the name and the slug, and nothing else")
    void responseCarriesOnlyWhatTheFormNeeds() {
        when(tenants.searchByDisplayNamePrefix(any(), any(), any()))
                .thenReturn(List.of(tenant("acme-tire", "Acme Tire & Auto")));

        List<TenantSearchResponse> results = service().search("acme");

        assertThat(results).containsExactly(new TenantSearchResponse("acme-tire", "Acme Tire & Auto"));
    }

    @Test
    @DisplayName("configuration may tighten the enumeration bound, never widen it")
    void configurationCannotWidenTheBound() {
        // These two values are the bound. A deployment override that served one-character queries
        // or handed back more than ten organizations would quietly undo what the ADR fixed.
        assertThatThrownBy(() -> new TenantSearchProperties(true, 2, 10))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minQueryLength");
        assertThatThrownBy(() -> new TenantSearchProperties(true, 3, 50))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxResults");

        // Tightening is allowed.
        assertThat(new TenantSearchProperties(true, 5, 3)).isNotNull();
    }

    @Test
    @DisplayName("the kill switch reports the directory as off")
    void killSwitchIsReported() {
        assertThat(service(false, 3, 10).isEnabled()).isFalse();
        assertThat(service().isEnabled()).isTrue();
    }
}
