package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.client.WebhookDestinationPolicy;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRegistrationResponse;
import com.positivity.securityservice.internal.dto.AuditExportWebhookRequest;
import com.positivity.securityservice.internal.dto.AuditExportWebhookResponse;
import com.positivity.securityservice.internal.entity.AuditExportWebhook;
import com.positivity.securityservice.internal.exception.AuditExportWebhookInvalidException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookNotRegisteredException;
import com.positivity.securityservice.internal.repository.AuditExportWebhookRepository;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registration of the bound tenant's audit export webhook destination (#2497).
 *
 * <p>One row per tenant: registering again updates it in place (new URL, new secret, new registrant
 * and time), so the destination's id survives a rotation and a {@code WEBHOOK} job requested before
 * it is still delivered when the URL did not change. Deleting removes the row; a later registration
 * gets a new id, and jobs requested against the old one are not delivered.
 *
 * <p>The signing secret is 32 bytes from {@link SecureRandom}, base64url without padding. It is
 * returned once, by {@link #register}, and never logged: log lines name the host only.
 */
@Slf4j
@Service
public class AuditExportWebhookServiceImpl implements AuditExportWebhookService {

    static final int SECRET_BYTES = 32;

    private static final Base64.Encoder SECRET_ENCODING = Base64.getUrlEncoder().withoutPadding();

    private final AuditExportWebhookRepository repository;
    private final WebhookDestinationPolicy policy;
    private final Clock clock;
    private final TransactionTemplate writeTx;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuditExportWebhookServiceImpl(
            @NonNull AuditExportWebhookRepository repository,
            @NonNull WebhookDestinationPolicy policy,
            @NonNull Clock clock,
            @NonNull PlatformTransactionManager transactionManager) {
        this.repository = repository;
        this.policy = policy;
        this.clock = clock;
        this.writeTx = new TransactionTemplate(transactionManager);
    }

    /**
     * Not {@code @Transactional}: the URL check resolves the host in DNS, which must not hold a
     * database connection, so only the write runs in a transaction.
     */
    @Override
    @NonNull
    public AuditExportWebhookRegistrationResponse register(@NonNull AuditExportWebhookRequest request) {
        WebhookDestinationPolicy.Verdict verdict = policy.validate(request.getUrl());
        URI destination = verdict.uri();
        if (!verdict.accepted() || destination == null) {
            throw new AuditExportWebhookInvalidException(verdict.problems());
        }
        String secret = newSecret();
        String actor = CurrentActor.resolve();
        AuditExportWebhookRegistrationResponse response = writeTx.execute(status -> {
            AuditExportWebhook webhook =
                    repository.findFirstByOrderByRegisteredAtDesc().orElseGet(AuditExportWebhook::new);
            boolean replacing = webhook.getWebhookId() != null;
            webhook.setUrl(destination.toString());
            webhook.setSigningSecret(secret);
            webhook.setRegisteredBy(actor);
            webhook.setRegisteredAt(clock.instant());
            AuditExportWebhook saved = repository.saveAndFlush(webhook);
            log.info(
                    "Audit export webhook {} for host {} by {}",
                    replacing ? "replaced" : "registered",
                    destination.getHost(),
                    actor);
            return AuditExportWebhookRegistrationResponse.builder()
                    .url(saved.getUrl())
                    .registeredAt(saved.getRegisteredAt())
                    .registeredBy(saved.getRegisteredBy())
                    .signingSecret(secret)
                    .build();
        });
        if (response == null) {
            throw new IllegalStateException("Audit export webhook registration returned no result");
        }
        return response;
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public AuditExportWebhookResponse get() {
        AuditExportWebhook webhook = repository
                .findFirstByOrderByRegisteredAtDesc()
                .orElseThrow(AuditExportWebhookNotRegisteredException::forLookup);
        return AuditExportWebhookResponse.builder()
                .url(webhook.getUrl())
                .registeredAt(webhook.getRegisteredAt())
                .registeredBy(webhook.getRegisteredBy())
                .build();
    }

    @Override
    @Transactional
    public void delete() {
        repository.findFirstByOrderByRegisteredAtDesc().ifPresent(webhook -> {
            repository.delete(webhook);
            log.info("Audit export webhook removed by {}", CurrentActor.resolve());
        });
    }

    private @NonNull String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return SECRET_ENCODING.encodeToString(bytes);
    }
}
