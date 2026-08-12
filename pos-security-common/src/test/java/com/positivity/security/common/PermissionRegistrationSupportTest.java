package com.positivity.security.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Unit tests for {@link PermissionRegistrationSupport}, the shared base class
 * every service extends to publish its permission catalog to
 * {@code pos-security-service} at startup.
 *
 * <p>
 * The behaviour that matters operationally is the failure posture: registration
 * retries a bounded number of times and then gives up with an error log, so a
 * security service that is slow to come up delays but never blocks a service's
 * startup. The disabled and empty-catalog short circuits matter too — they are
 * what keeps test and offline profiles from making outbound calls at all.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PermissionRegistrationSupport — startup permission registration")
class PermissionRegistrationSupportTest {

    private static final String SECURITY_URL = "http://pos-security-service:8080";
    private static final int MAX_RETRIES = 5;

    private static final List<PermissionDefinition> PERMISSIONS = List.of(
            PermissionDefinition.of("catalog:product:view", "View products"),
            PermissionDefinition.of("catalog:product:create", "Create products"));

    @Mock
    private RestClient.Builder restClientBuilder;

    @Mock
    private RestClient restClient;

    @Mock(answer = Answers.RETURNS_SELF)
    private RestClient.RequestBodyUriSpec requestBodyUriSpec;

    @Mock
    private RestClient.ResponseSpec responseSpec;

    @Captor
    private ArgumentCaptor<Object> requestBody;

    @BeforeEach
    void setUp() {
        when(restClientBuilder.baseUrl(anyString())).thenReturn(restClientBuilder);
        when(restClientBuilder.build()).thenReturn(restClient);

        lenient().when(restClient.post()).thenReturn(requestBodyUriSpec);
        lenient().when(requestBodyUriSpec.retrieve()).thenReturn(responseSpec);
        lenient().when(responseSpec.toBodilessEntity()).thenReturn(null);
    }

    @AfterEach
    void clearSecretProperty() {
        System.clearProperty(SecurityApiConstants.PERMISSION_SECRET_PROPERTY);
    }

    /** Minimal concrete subclass supplying an inline permission catalog. */
    private final class InlineRegistration extends PermissionRegistrationSupport {
        private final List<PermissionDefinition> permissions;

        InlineRegistration(boolean enabled, List<PermissionDefinition> permissions) {
            super(restClientBuilder, SECURITY_URL, "catalog", "pos-catalog", enabled);
            this.permissions = permissions;
        }

        @Override
        protected List<PermissionDefinition> getPermissions() {
            return permissions;
        }
    }

    /** Subclass that supplies no catalog, exercising the base-class default. */
    private final class NoManifestRegistration extends PermissionRegistrationSupport {
        NoManifestRegistration() {
            super(restClientBuilder, SECURITY_URL, "catalog", "pos-catalog");
        }
    }

    @Test
    @DisplayName("posts the permission catalog once when the security service accepts it")
    void run_whenRegistrationSucceeds_postsOnce() {
        new InlineRegistration(true, PERMISSIONS).run(null);

        verify(restClient, times(1)).post();
        verify(responseSpec, times(1)).toBodilessEntity();
    }

    @Test
    @DisplayName("sends the domain, service name, version, and every permission in the request body")
    void run_sendsFullCatalogInRequestBody() {
        new InlineRegistration(true, PERMISSIONS).run(null);

        verify(requestBodyUriSpec).body(requestBody.capture());
        assertThat(requestBody.getValue())
                .isInstanceOfSatisfying(PermissionRegistrationSupport.PermissionRegistrationRequest.class, request -> {
                    assertThat(request.domain()).isEqualTo("catalog");
                    assertThat(request.serviceName()).isEqualTo("pos-catalog");
                    assertThat(request.version()).isEqualTo("1.0");
                    assertThat(request.permissions())
                            .extracting(PermissionRegistrationSupport.PermissionDto::name)
                            .containsExactly("catalog:product:view", "catalog:product:create");
                });
    }

    @Test
    @DisplayName("makes no outbound call when registration is disabled")
    void run_whenDisabled_makesNoCall() {
        new InlineRegistration(false, PERMISSIONS).run(null);

        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("makes no outbound call when the permission catalog is empty")
    void run_whenCatalogEmpty_makesNoCall() {
        new InlineRegistration(true, List.of()).run(null);

        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("makes no outbound call when the permission catalog is null")
    void run_whenCatalogNull_makesNoCall() {
        new InlineRegistration(true, null).run(null);

        verify(restClient, never()).post();
    }

    @Test
    @DisplayName("retries after a transport failure and stops once a call succeeds")
    void run_whenFirstAttemptFails_retriesThenSucceeds() {
        when(responseSpec.toBodilessEntity())
                .thenThrow(new RestClientException("connection refused"))
                .thenReturn(null);

        runUninterruptibly(() -> new InlineRegistration(true, PERMISSIONS).run(null));

        verify(restClient, times(2)).post();
    }

    @Test
    @DisplayName("gives up after the retry budget without throwing — startup must not block on the security service")
    void run_whenAllAttemptsFail_givesUpWithoutThrowing() {
        when(responseSpec.toBodilessEntity()).thenThrow(new RestClientException("connection refused"));

        assertThatNoException()
                .isThrownBy(() -> runUninterruptibly(() -> new InlineRegistration(true, PERMISSIONS).run(null)));

        verify(restClient, times(MAX_RETRIES)).post();
    }

    @Test
    @DisplayName("sends the shared-secret header when the registration secret is configured")
    void run_withConfiguredSecret_sendsSecretHeader() {
        System.setProperty(SecurityApiConstants.PERMISSION_SECRET_PROPERTY, "s3cret");

        new InlineRegistration(true, PERMISSIONS).run(null);

        verify(requestBodyUriSpec).header(SecurityApiConstants.PERMISSION_SECRET_HEADER, "s3cret");
    }

    @Test
    @DisplayName("omits the shared-secret header when no registration secret is configured")
    void run_withoutSecret_omitsSecretHeader() {
        // The secret also resolves from the environment, which this test cannot
        // unset; skip rather than fail when a developer machine exports it.
        assumeTrue(
                !SecurityApiConstants.hasSecret(System.getenv(SecurityApiConstants.PERMISSION_SECRET_ENV_VAR)),
                SecurityApiConstants.PERMISSION_SECRET_ENV_VAR + " is set in this environment");

        new InlineRegistration(true, PERMISSIONS).run(null);

        verify(requestBodyUriSpec, never()).header(eq(SecurityApiConstants.PERMISSION_SECRET_HEADER), anyString());
    }

    @Test
    @DisplayName("a subclass with neither a manifest nor an override fails loudly rather than registering nothing")
    void getPermissions_withNoManifestAndNoOverride_throws() {
        NoManifestRegistration registration = new NoManifestRegistration();

        assertThatIllegalStateException().isThrownBy(() -> registration.run(null));
    }

    /**
     * Runs the registration with the calling thread pre-interrupted so the
     * 2-second retry back-off returns immediately: {@code Thread.sleep} on an
     * interrupted thread throws at once, and the production code re-sets the flag
     * in its handler, keeping every subsequent back-off instant too. Without this
     * the retry tests would idle for eight seconds. The flag is cleared afterwards
     * so it cannot leak into another test on the same thread.
     */
    private void runUninterruptibly(Runnable registration) {
        Thread.currentThread().interrupt();
        try {
            registration.run();
        } finally {
            Thread.interrupted();
        }
    }
}
