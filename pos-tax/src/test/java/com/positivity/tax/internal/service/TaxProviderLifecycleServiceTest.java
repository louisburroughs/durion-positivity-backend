package com.positivity.tax.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.tax.common.dto.TaxCalculationRequest;
import com.positivity.tax.common.dto.TaxCalculationResponse;
import com.positivity.tax.common.dto.TaxProviderTransactionResult;
import com.positivity.tax.common.enums.TaxProviderTransactionStatus;
import com.positivity.tax.internal.entity.TaxProviderTransaction;
import com.positivity.tax.internal.exception.TaxCalculationException;
import com.positivity.tax.internal.repository.TaxProviderTransactionRepository;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantIterator;
import com.positivity.tenancy.TenantResolver;
import com.positivity.tenancy.testing.TenantTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Story T6 / decision D-T3: the lifecycle service records commit/void rows, is
 * idempotent
 * on {@code referenceId}, records PENDING_COMMIT when the provider fails (never
 * blocking the
 * sale) and the re-commit job promotes PENDING_COMMIT → COMMITTED when the
 * provider recovers.
 *
 * <p>
 * Exercised against H2 with the real Flyway
 * {@code V2__tax_provider_transaction.sql}
 * migration and Hibernate schema validation.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_tax_lifecycle;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            "spring.jpa.hibernate.ddl-auto=create-drop",
            "spring.flyway.enabled=false"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class TaxProviderLifecycleServiceTest {

    @Autowired
    private TaxProviderTransactionRepository repository;

    @Autowired
    private TenantIterator tenantIterator;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private static final String PLUGIN = "ZZ_SELF";

    private ControllableProvider provider;
    private SelfHostedTaxPlugin plugin;
    private TaxProviderLifecycleService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        provider = new ControllableProvider();
        TaxProviderSelector selector = mock(TaxProviderSelector.class);
        when(selector.select()).thenReturn(provider);
        // CAP:550 S32a: a per-country plug-in from a made-up fixture country (not tax law).
        plugin = new SelfHostedTaxPlugin(
                new TaxCountryProfiles(TaxProfileFixtures.bind(TaxProfileFixtures.MADE_UP_COUNTRY))
                        .profile("ZZ")
                        .orElseThrow(),
                Clock.systemUTC());
        when(selector.isSelfHosted(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.<String>getArgument(0).endsWith("_SELF"));
        when(selector.lifecycleProviderFor(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> {
                    String name = inv.getArgument(0);
                    if (PLUGIN.equals(name)) {
                        return plugin;
                    }
                    return name.endsWith("_SELF") ? new RetiredSelfHostedPlugin(name) : provider;
                });
        ObjectProvider<MeterRegistry> meterRegistry = mock(ObjectProvider.class);
        when(meterRegistry.getIfAvailable()).thenReturn(null);
        TaxProviderTransactionResolver resolver =
                new TaxProviderTransactionResolver(repository, Clock.systemUTC(), tenantResolver());
        service = new TaxProviderLifecycleService(
                selector, repository, resolver, meterRegistry, tenantIterator, transactionManager);
    }

    @Test
    @DisplayName("commit records a COMMITTED row with the external transaction id")
    void commitRecordsCommittedRow() {
        UUID ref = UUID.randomUUID();

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(row.getReferenceType()).isEqualTo("INVOICE");
        assertThat(row.getProvider()).isEqualTo("FAKE");
        assertThat(row.getExternalTransactionId()).isEqualTo("ext-123");
        assertThat(row.getAttempts()).isEqualTo(1);
    }

    @Test
    @DisplayName("void records a VOIDED row")
    void voidRecordsVoidedRow() {
        UUID ref = UUID.randomUUID();

        TaxProviderTransactionResult result = service.voidTransaction(ref);

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
    }

    @Test
    @DisplayName("commit is idempotent: the same referenceId twice yields exactly one committed row")
    void commitIsIdempotent() {
        UUID ref = UUID.randomUUID();

        service.commit(ref, "INVOICE");
        TaxProviderTransactionResult second = service.commit(ref, "INVOICE");

        assertThat(second.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(repository.count()).isEqualTo(1);
        assertThat(repository.findByReferenceId(ref).orElseThrow().getAttempts())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("commit failure records PENDING_COMMIT without throwing (estimate-and-true-up)")
    void commitFailureRecordsPendingCommit() {
        UUID ref = UUID.randomUUID();
        provider.failCommit = true;

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);
        assertThat(row.getLastError()).contains("provider down");
        assertThat(service.pendingCommitBacklog()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("re-commit job promotes PENDING_COMMIT to COMMITTED when the provider recovers")
    void recommitPromotesPendingToCommitted() {
        UUID ref = UUID.randomUUID();
        provider.failCommit = true;
        service.commit(ref, "INVOICE");
        assertThat(service.pendingCommitBacklog()).isEqualTo(1.0);

        // Provider recovers.
        provider.failCommit = false;
        service.recommitPending();

        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(row.getExternalTransactionId()).isEqualTo("ext-123");
        assertThat(row.getAttempts()).isEqualTo(2);
        assertThat(service.pendingCommitBacklog()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("S32a: a plug-in-priced document is logged ESTIMATED with the plug-in, and the re-commit job skips it")
    void pluginPricingIsLoggedEstimated() {
        UUID ref = UUID.randomUUID();

        service.recordPricing(ref, "INVOICE", PLUGIN);

        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.ESTIMATED);
        assertThat(row.getProvider()).isEqualTo(PLUGIN);
        assertThat(service.pendingCommitBacklog()).isZero();
        service.recommitPending();
        assertThat(repository.findByReferenceId(ref).orElseThrow().getStatus())
                .isEqualTo(TaxProviderTransactionStatus.ESTIMATED);
    }

    @Test
    @DisplayName("S32a AC 4: commit of a plug-in-priced document is a logged no-op naming the plug-in")
    void pluginCommitIsLoggedNoOp() {
        UUID ref = UUID.randomUUID();
        service.recordPricing(ref, "INVOICE", PLUGIN);
        // The switch's provider would fail: a COMMITTED result proves the plug-in answered.
        provider.failCommit = true;

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(row.getProvider()).isEqualTo(PLUGIN);
        assertThat(row.getExternalTransactionId()).isNull();
    }

    @Test
    @DisplayName("S32a AC 4: void of a plug-in-priced document is a logged no-op naming the plug-in")
    void pluginVoidIsLoggedNoOp() {
        UUID ref = UUID.randomUUID();
        service.recordPricing(ref, "INVOICE", PLUGIN);
        service.commit(ref, "INVOICE");

        TaxProviderTransactionResult result = service.voidTransaction(ref);

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(TaxProviderTransactionStatus.VOIDED);
        assertThat(row.getProvider()).isEqualTo(PLUGIN);
        assertThat(row.getExternalTransactionId()).isNull();
    }

    @Test
    @DisplayName("S32a: a document priced by the switch with no row records nothing (today's behaviour)")
    void switchPricingRecordsNothing() {
        UUID ref = UUID.randomUUID();

        service.recordPricing(ref, "INVOICE", "FAKE");

        assertThat(repository.findByReferenceId(ref)).isEmpty();
    }

    @Test
    @DisplayName("S32a: re-pricing an uncommitted plug-in document through the switch re-points the log")
    void repricingThroughTheSwitchRepointsTheLog() {
        UUID ref = UUID.randomUUID();
        service.recordPricing(ref, "INVOICE", PLUGIN);

        service.recordPricing(ref, "INVOICE", "FAKE");
        service.commit(ref, "INVOICE");

        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getProvider()).isEqualTo("FAKE");
        assertThat(row.getExternalTransactionId()).isEqualTo("ext-123");
    }

    @Test
    @DisplayName("S32a: a PENDING_COMMIT document of the switch is never re-pointed to a plug-in")
    void pendingCommitIsNeverRepointed() {
        UUID ref = UUID.randomUUID();
        provider.failCommit = true;
        service.commit(ref, "INVOICE");
        assertThat(repository.findByReferenceId(ref).orElseThrow().getStatus())
                .isEqualTo(TaxProviderTransactionStatus.PENDING_COMMIT);

        // A committable calculation for a plug-in country reusing this referenceId.
        service.recordPricing(ref, "INVOICE", PLUGIN);

        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getProvider()).isEqualTo("FAKE");
        // The re-commit job still reaches the real provider, not the no-op plug-in.
        provider.failCommit = false;
        service.recommitPending();
        TaxProviderTransaction after = repository.findByReferenceId(ref).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        assertThat(after.getExternalTransactionId()).isEqualTo("ext-123");
    }

    @Test
    @DisplayName("S32a: a FAILED void of the switch is never re-pointed to a plug-in")
    void failedVoidIsNeverRepointed() {
        UUID ref = UUID.randomUUID();
        service.commit(ref, "INVOICE");
        provider.failVoid = true;
        service.voidTransaction(ref);
        assertThat(repository.findByReferenceId(ref).orElseThrow().getStatus())
                .isEqualTo(TaxProviderTransactionStatus.FAILED);

        service.recordPricing(ref, "INVOICE", PLUGIN);

        assertThat(repository.findByReferenceId(ref).orElseThrow().getProvider())
                .isEqualTo("FAKE");
    }

    @Test
    @DisplayName("S32a: a VOIDED document (no live provider document) re-priced by a plug-in is re-pointed")
    void voidedDocumentIsRepointed() {
        UUID ref = UUID.randomUUID();
        service.commit(ref, "INVOICE");
        service.voidTransaction(ref);

        service.recordPricing(ref, "INVOICE", PLUGIN);

        assertThat(repository.findByReferenceId(ref).orElseThrow().getProvider())
                .isEqualTo(PLUGIN);
    }

    @Test
    @DisplayName("S32a: a committed document is never re-pointed")
    void committedDocumentIsNeverRepointed() {
        UUID ref = UUID.randomUUID();
        service.commit(ref, "INVOICE");

        service.recordPricing(ref, "INVOICE", PLUGIN);

        assertThat(repository.findByReferenceId(ref).orElseThrow().getProvider())
                .isEqualTo("FAKE");
    }

    @Test
    @DisplayName(
            "S32a: a document priced by a plug-in whose profile was since removed still commits there, never via the switch")
    void retiredPluginStillOwnsItsDocuments() {
        UUID ref = UUID.randomUUID();
        service.recordPricing(ref, "INVOICE", "QQ_SELF");
        provider.failCommit = true;

        TaxProviderTransactionResult result = service.commit(ref, "INVOICE");

        assertThat(result.status()).isEqualTo(TaxProviderTransactionStatus.COMMITTED);
        TaxProviderTransaction row = repository.findByReferenceId(ref).orElseThrow();
        assertThat(row.getProvider()).isEqualTo("QQ_SELF");
        assertThat(row.getExternalTransactionId()).isNull();
    }

    /** Test double whose commit can be toggled to fail. */
    private static final class ControllableProvider implements TaxProviderClient {
        private boolean failCommit;
        private boolean failVoid;

        @Override
        @NonNull
        public String providerName() {
            return "FAKE";
        }

        @Override
        @NonNull
        public TaxCalculationResponse estimate(@NonNull TaxCalculationRequest request) {
            throw new UnsupportedOperationException("not used in lifecycle test");
        }

        @Override
        @NonNull
        public TaxCalculationResponse refund(
                @NonNull TaxCalculationRequest request, @NonNull UUID originalReferenceId) {
            throw new UnsupportedOperationException("not used in lifecycle test");
        }

        @Override
        @NonNull
        public TaxProviderTransactionResult commit(@NonNull UUID referenceId) {
            if (failCommit) {
                throw new TaxCalculationException("provider down");
            }
            return new TaxProviderTransactionResult(
                    referenceId, TaxProviderTransactionStatus.COMMITTED, "ext-123", "ok");
        }

        @Override
        @NonNull
        public TaxProviderTransactionResult voidTransaction(@NonNull UUID referenceId) {
            if (failVoid) {
                throw new TaxCalculationException("provider down");
            }
            return new TaxProviderTransactionResult(referenceId, TaxProviderTransactionStatus.VOIDED, "ext-123", "ok");
        }
    }

    /** Resolves the alpha default tenant, as an unbound path does at runtime (ADR-0062). */
    private static TenantResolver tenantResolver() {
        TenancyProperties tenancy = new TenancyProperties();
        tenancy.setDefaultTenantId(TenantTestSupport.TENANT_A);
        return new TenantResolver(tenancy);
    }
}
