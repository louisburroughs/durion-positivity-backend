package com.positivity.securityservice.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.securityservice.internal.client.WebhookDestinationPolicy;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRegistrationResponse;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRequest;
import com.positivity.securityservice.internal.dto.AuditExportWebhookResponse;
import com.positivity.securityservice.internal.entity.AuditExportWebhook;
import com.positivity.securityservice.internal.exception.AuditExportWebhookInvalidException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException;
import com.positivity.securityservice.internal.repository.AuditExportWebhookRepository;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * #2497: registering the tenant's one audit export webhook — the destination rules decide, a new
 * signing secret is issued on every registration and returned only then, the read never carries it,
 * and removal is idempotent. The repository is an in-memory single row, as {@code UNIQUE (tenant_id)}
 * makes the table for one tenant.
 */
@DisplayName("AuditExportWebhookServiceImpl - the tenant's audit export webhook (#2497)")
class AuditExportWebhookServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-12T09:30:00Z");
    private static final String URL = "https://siem.example.com/hooks/audit";
    private static final UUID WEBHOOK_ID = UUID.fromString("01960000-0000-7000-8000-00000000b001");

    private final AuditExportWebhookRepository repository = mock(AuditExportWebhookRepository.class);
    private final WebhookDestinationPolicy policy = mock(WebhookDestinationPolicy.class);
    private final AuditExportWebhookServiceImpl service = new AuditExportWebhookServiceImpl(
            repository, policy, Clock.fixed(NOW, ZoneOffset.UTC), mock(PlatformTransactionManager.class));

    private AuditExportWebhook row;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.getContext()
                .setAuthentication(
                        new TestingAuthenticationToken("admin.alpha", null, "security:audit_export:webhook_manage"));
        when(repository.findFirstByOrderByRegisteredAtDesc()).thenAnswer(inv -> Optional.ofNullable(row));
        when(repository.saveAndFlush(any(AuditExportWebhook.class))).thenAnswer(inv -> {
            AuditExportWebhook saved = inv.getArgument(0);
            if (saved.getWebhookId() == null) {
                saved.setWebhookId(WEBHOOK_ID);
            }
            row = saved;
            return saved;
        });
        org.mockito.Mockito.doAnswer(inv -> {
                    row = null;
                    return null;
                })
                .when(repository)
                .delete(any(AuditExportWebhook.class));
        when(policy.validate(URL)).thenReturn(new WebhookDestinationPolicy.Verdict(URI.create(URL), List.of()));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static AuditExportWebhookRequest request(String url) {
        return AuditExportWebhookRequest.builder().url(url).build();
    }

    @Test
    @DisplayName("registering stores the destination and answers it once with a fresh 32-byte base64url secret")
    void registerIssuesSecretOnce() {
        AuditExportWebhookRegistrationResponse response = service.register(request(URL));

        assertThat(response.getUrl()).isEqualTo(URL);
        assertThat(response.getRegisteredAt()).isEqualTo(NOW);
        assertThat(response.getRegisteredBy()).isEqualTo("admin.alpha");
        assertThat(response.getSigningSecret()).hasSize(43).matches("[A-Za-z0-9_-]{43}");
        assertThat(Base64.getUrlDecoder().decode(response.getSigningSecret())).hasSize(32);
        assertThat(row.getUrl()).isEqualTo(URL);
        assertThat(row.getSigningSecret()).isEqualTo(response.getSigningSecret());
        assertThat(row.getRegisteredBy()).isEqualTo("admin.alpha");
        assertThat(response.toString()).doesNotContain(response.getSigningSecret());
    }

    @Test
    @DisplayName("registering again replaces the URL and rotates the secret on the same registration")
    void reRegisterRotates() {
        String other = "https://other.example.org/hook";
        when(policy.validate(other)).thenReturn(new WebhookDestinationPolicy.Verdict(URI.create(other), List.of()));
        String first = service.register(request(URL)).getSigningSecret();

        AuditExportWebhookRegistrationResponse second = service.register(request(other));

        assertThat(second.getUrl()).isEqualTo(other);
        assertThat(second.getSigningSecret()).isNotEqualTo(first);
        assertThat(row.getWebhookId()).isEqualTo(WEBHOOK_ID);
        assertThat(row.getUrl()).isEqualTo(other);
        assertThat(row.getSigningSecret()).isEqualTo(second.getSigningSecret());
        verify(repository, times(2)).saveAndFlush(any());
    }

    @Test
    @DisplayName("a URL the destination rules refuse is reported field by field and nothing is stored")
    void invalidUrlRefused() {
        when(policy.validate("http://10.0.0.7/x"))
                .thenReturn(new WebhookDestinationPolicy.Verdict(
                        null, List.of("url must use https", "url must name its host by DNS name, not by IP address")));

        assertThatThrownBy(() -> service.register(request("http://10.0.0.7/x")))
                .isInstanceOf(AuditExportWebhookInvalidException.class)
                .satisfies(e -> assertThat(((AuditExportWebhookInvalidException) e).fieldMessages())
                        .containsExactly(
                                "url must use https", "url must name its host by DNS name, not by IP address"));
        verify(repository, never()).saveAndFlush(any());
        assertThat(row).isNull();
    }

    @Test
    @DisplayName("reading answers the destination without its secret, never again carrying it")
    void getHidesSecret() {
        String secret = service.register(request(URL)).getSigningSecret();

        AuditExportWebhookResponse read = service.get();

        assertThat(read.getUrl()).isEqualTo(URL);
        assertThat(read.getRegisteredAt()).isEqualTo(NOW);
        assertThat(read.getRegisteredBy()).isEqualTo("admin.alpha");
        String json = JsonMapper.builder().build().writeValueAsString(read);
        assertThat(json).doesNotContain(secret).doesNotContain("signingSecret");
    }

    @Test
    @DisplayName("reading with nothing registered is AUDIT_EXPORT_WEBHOOK_NOT_REGISTERED as a lookup (404)")
    void getUnregistered() {
        assertThatThrownBy(service::get)
                .isInstanceOf(AuditExportWebhookNotRegisteredException.class)
                .satisfies(e -> assertThat(((AuditExportWebhookNotRegisteredException) e).exportRequest())
                        .isFalse());
    }

    @Test
    @DisplayName("removing deletes the registration, and removing again (or with none) is not an error")
    void deleteIsIdempotent() {
        service.register(request(URL));

        service.delete();
        assertThatCode(service::delete).doesNotThrowAnyException();

        assertThat(row).isNull();
        verify(repository, times(1)).delete(any(AuditExportWebhook.class));
        assertThatThrownBy(service::get).isInstanceOf(AuditExportWebhookNotRegisteredException.class);
    }
}
