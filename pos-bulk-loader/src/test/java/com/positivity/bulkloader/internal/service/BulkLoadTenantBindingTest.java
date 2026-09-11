package com.positivity.bulkloader.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.exception.BulkLoadTenantException;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.StaticTenantRegistry;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/**
 * The target-tenant rules of a bulk-load job (ADR-0062, plan WS8): named and active, or the
 * platform tenant, which is loadable as platform data whether or not the registry lists it
 * active; a bound caller — the platform tenant's operator included — may only load into its own
 * tenant; an unbound caller may name no tenant at all — the transitional default applies then,
 * with a WARN — but may not name a target explicitly (Copilot review of PR #1955, third round).
 * When the target resolves to the platform tenant, the job's domain type must also be one of the
 * platform's own packs (Copilot review of PR #1955, Finding 5).
 */
@DisplayName("BulkLoadTenantBinding: which tenant a job loads into")
class BulkLoadTenantBindingTest {

    private static final UUID ALPHA = TenantTestSupport.TENANT_A;
    private static final UUID BETA = TenantTestSupport.TENANT_B;
    private static final UUID SUSPENDED = UUID.fromString("01900000-0000-7000-8000-000000000009");

    /** An ordinary tenant-data domain, used wherever the domain type itself is not what the test pins. */
    private static final DomainType ORDINARY_DOMAIN = DomainType.CATALOG_PRODUCT;

    private final TenancyProperties properties = new TenancyProperties();
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        logs.start();
        ((Logger) LoggerFactory.getLogger(BulkLoadTenantBinding.class)).addAppender(logs);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        ((Logger) LoggerFactory.getLogger(BulkLoadTenantBinding.class)).detachAppender(logs);
    }

    /** Alpha and beta are the cell's active tenants; SUSPENDED is not in the registry. */
    private BulkLoadTenantBinding binding() {
        properties.setTenants(List.of(ALPHA, BETA));
        return new BulkLoadTenantBinding(new StaticTenantRegistry(properties), properties);
    }

    @Test
    @DisplayName("a request naming no tenant is refused with BULK_JOB_TENANT_REQUIRED when no default applies")
    void noTenantAndNoDefaultIsRejected() {
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(null, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_REQUIRED);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("a request naming no tenant uses the transitional default and says so at WARN")
    void noTenantWithTheDefaultUsesItAndWarns() {
        properties.setDefaultTenantId(ALPHA);
        BulkLoadTenantBinding binding = binding();

        assertThat(binding.resolveTarget(null, ORDINARY_DOMAIN)).isEqualTo(ALPHA);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("transitional default tenant " + ALPHA);
        });
    }

    /**
     * Copilot review of PR #1955, third round: {@code tenantId} is a target selector for an
     * already-bound caller only (AGENTS.md's tenancy paragraph), never an authorization input. An
     * unbound caller naming an explicit target used to be resolved and bound like any other
     * request, which let a caller nobody had bound to any tenant load into whichever active tenant
     * it named.
     */
    @Test
    @DisplayName("an unbound caller naming an explicit target is refused: 403 BULK_JOB_TENANT_UNBOUND_TARGET_FORBIDDEN")
    void unboundCallerNamingATargetIsRejected() {
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(BETA, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_UNBOUND_TARGET_FORBIDDEN);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("an unbound caller naming an explicit target is refused even when it names its own future tenant"
            + " or the platform tenant: there is no binding yet for either to match")
    void unboundCallerNamingAnyTargetIsRejectedRegardlessOfWhichTenant() {
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE))
                .isInstanceOfSatisfying(
                        BulkLoadTenantException.class,
                        e -> assertThat(e.getCode())
                                .isEqualTo(BulkLoadTenantException.TENANT_UNBOUND_TARGET_FORBIDDEN));
    }

    @Test
    @DisplayName("an unbound caller naming no tenant still falls back to the transitional default, unaffected"
            + " by the new unbound-target refusal")
    void unboundCallerWithNoTargetStillUsesTheDefault() {
        properties.setDefaultTenantId(ALPHA);
        BulkLoadTenantBinding binding = binding();

        assertThat(binding.resolveTarget(null, ORDINARY_DOMAIN)).isEqualTo(ALPHA);
        assertThat(logs.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("transitional default tenant " + ALPHA);
        });
    }

    @Test
    @DisplayName("a tenant the registry does not list as active is refused with BULK_JOB_TENANT_UNKNOWN")
    void unknownTenantIsRejected() {
        // Bound to the tenant it names: this test is about the isLoadable check, not the
        // unbound-target refusal a naming-any-other-tenant call would hit first.
        TenantContext.bind(SUSPENDED);
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(SUSPENDED, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_UNKNOWN);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                });
    }

    @Test
    @DisplayName("the platform tenant is loadable (platform data) although no registry lists it as active")
    void platformTenantIsLoadable() {
        TenantContext.bind(PlatformTenant.ID);
        assertThat(binding().resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE))
                .isEqualTo(PlatformTenant.ID);
    }

    @Test
    @DisplayName("a caller bound to a tenant loads into that tenant")
    void boundCallerLoadsIntoItsOwnTenant() {
        TenantContext.bind(ALPHA);
        assertThat(binding().resolveTarget(ALPHA, ORDINARY_DOMAIN)).isEqualTo(ALPHA);
    }

    @Test
    @DisplayName("a caller bound to a tenant may not load into another one: 403 BULK_JOB_TENANT_FORBIDDEN")
    void boundCallerMayNotLoadIntoAnotherTenant() {
        TenantContext.bind(ALPHA);
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(BETA, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_FORBIDDEN);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        assertThatThrownBy(() -> binding.resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE))
                .as("nor into the platform tenant")
                .isInstanceOfSatisfying(
                        BulkLoadTenantException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_FORBIDDEN));
    }

    @Test
    @DisplayName("a platform-tenant caller loads platform data only: another tenant is 403, not an impersonation")
    void platformCallerMayNotLoadIntoAnotherTenant() {
        // A job created in tenant B by a platform-bound caller could never be continued: upload,
        // process and status run under the request's own binding and operator id, so the platform
        // token cannot see the job and a token of B fails its ownership check. Refused here rather
        // than left as a row nobody can use; reopening it needs an impersonation credential, which
        // is outside WS8 (see BulkLoadTenantBinding's class comment).
        TenantContext.bind(PlatformTenant.ID);
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(BETA, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_FORBIDDEN);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
        assertThat(binding.resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE))
                .as("its own tenant, the role template's home, is what it may load")
                .isEqualTo(PlatformTenant.ID);
        assertThatThrownBy(() -> binding.resolveTarget(SUSPENDED, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(
                        BulkLoadTenantException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_UNKNOWN));
    }

    @Test
    @DisplayName("a caller bound to a tenant other than the default asking for no tenant gets the default, not its own")
    void theDefaultIsTheDefaultEvenForABoundCaller() {
        // Transitional mode only: every request the gateway has not stamped is bound to the default
        // by TenantContextFilter, so in practice bound == default here. A caller bound elsewhere
        // that names no tenant is refused (it may not load into the default).
        properties.setDefaultTenantId(ALPHA);
        TenantContext.bind(BETA);
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(null, ORDINARY_DOMAIN))
                .isInstanceOfSatisfying(
                        BulkLoadTenantException.class,
                        e -> assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_FORBIDDEN));
    }

    /**
     * Finding 5, Copilot review of PR #1955: the platform tenant is platform data's home (the role
     * template packs) and nothing else. Without this check a caller bound to the platform tenant
     * could create a job of any domain type there, and the batch would relay the platform binding
     * into a sibling service that has no idea the tenant is special.
     */
    @Test
    @DisplayName(
            "the platform tenant refuses a domain type outside its own packs: 403 BULK_JOB_TENANT_DOMAIN_FORBIDDEN")
    void platformTenantRefusesADomainTypeItDoesNotOwn() {
        TenantContext.bind(PlatformTenant.ID);
        BulkLoadTenantBinding binding = binding();

        assertThatThrownBy(() -> binding.resolveTarget(PlatformTenant.ID, DomainType.CATALOG_PRODUCT))
                .isInstanceOfSatisfying(BulkLoadTenantException.class, e -> {
                    assertThat(e.getCode()).isEqualTo(BulkLoadTenantException.TENANT_DOMAIN_FORBIDDEN);
                    assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                });
    }

    @Test
    @DisplayName("the platform tenant accepts both role-template pack domain types")
    void platformTenantAcceptsBothRoleTemplateDomainTypes() {
        TenantContext.bind(PlatformTenant.ID);
        BulkLoadTenantBinding binding = binding();

        assertThat(binding.resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE))
                .isEqualTo(PlatformTenant.ID);
        assertThat(binding.resolveTarget(PlatformTenant.ID, DomainType.SECURITY_ROLE_PERMISSION))
                .isEqualTo(PlatformTenant.ID);
    }

    @Test
    @DisplayName("an ordinary tenant accepts any domain type: the platform restriction is platform-only")
    void ordinaryTenantAcceptsAnyDomainType() {
        TenantContext.bind(ALPHA);
        assertThat(binding().resolveTarget(ALPHA, DomainType.CATALOG_PRODUCT)).isEqualTo(ALPHA);
    }
}
