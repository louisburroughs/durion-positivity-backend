package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(MockitoExtension.class)
class StagingLocationResolverTest {

    private static final UUID DEFAULT_STAGING_LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Mock
    private SiteDefaultsService siteDefaultsService;

    private StagingLocationResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StagingLocationResolver(siteDefaultsService);
    }

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void resolveStagingLocationId_noConfigNoSite_returnsHardcodedDefault() {
        assertThat(resolver.resolveStagingLocationId()).isEqualTo(DEFAULT_STAGING_LOCATION_ID);
    }

    @Test
    void resolveStagingLocationId_configuredStagingLocation_returnsConfiguredValue() {
        UUID configured = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
        ReflectionTestUtils.setField(resolver, "configuredStagingLocationId", configured.toString());

        assertThat(resolver.resolveStagingLocationId()).isEqualTo(configured);
    }

    @Test
    void resolveStagingLocationId_invalidConfiguredStagingLocation_throwsIllegalState() {
        ReflectionTestUtils.setField(resolver, "configuredStagingLocationId", "not-a-uuid");

        assertThatThrownBy(() -> resolver.resolveStagingLocationId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.inventory.receiving.staging-location-id");
    }

    @Test
    void resolveStagingLocationId_configuredSiteWithDefault_usesSiteDefault() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID siteDefault = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
        ReflectionTestUtils.setField(resolver, "configuredSiteId", siteId.toString());
        when(siteDefaultsService.getDefaultStagingLocationId(siteId)).thenReturn(Optional.of(siteDefault));

        assertThat(resolver.resolveStagingLocationId()).isEqualTo(siteDefault);
    }

    @Test
    void resolveStagingLocationId_configuredSiteWithoutDefault_fallsBackToConfiguredStaging() {
        UUID siteId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID configuredStaging = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
        ReflectionTestUtils.setField(resolver, "configuredSiteId", siteId.toString());
        ReflectionTestUtils.setField(resolver, "configuredStagingLocationId", configuredStaging.toString());
        when(siteDefaultsService.getDefaultStagingLocationId(siteId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveStagingLocationId()).isEqualTo(configuredStaging);
    }

    @Test
    void resolveStagingLocationId_requestScopedHeaderOverridesConfiguredSite() {
        UUID configuredSiteId = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID headerSiteId = UUID.fromString("00000000-0000-0000-0000-0000000000ee");
        UUID headerSiteDefault = UUID.fromString("00000000-0000-0000-0000-0000000000ff");
        ReflectionTestUtils.setField(resolver, "configuredSiteId", configuredSiteId.toString());
        when(siteDefaultsService.getDefaultStagingLocationId(headerSiteId)).thenReturn(Optional.of(headerSiteDefault));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Site-Id", headerSiteId.toString());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(resolver.resolveStagingLocationId()).isEqualTo(headerSiteDefault);
    }

    @Test
    void resolveStagingLocationId_requestScopedPathVariable_usesSiteDefault() {
        UUID pathSiteId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
        UUID pathSiteDefault = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
        when(siteDefaultsService.getDefaultStagingLocationId(pathSiteId)).thenReturn(Optional.of(pathSiteDefault));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE, Map.of("siteId", pathSiteId.toString()));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        assertThat(resolver.resolveStagingLocationId()).isEqualTo(pathSiteDefault);
    }

    @Test
    void resolveStagingLocationId_invalidConfiguredSiteId_throwsIllegalState() {
        ReflectionTestUtils.setField(resolver, "configuredSiteId", "not-a-uuid");

        assertThatThrownBy(() -> resolver.resolveStagingLocationId())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pos.inventory.receiving.site-id");
    }
}
