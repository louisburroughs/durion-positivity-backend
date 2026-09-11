package com.positivity.mcp.internal.service;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.mcp.internal.entity.NltiSession;
import com.positivity.mcp.internal.exception.SessionNotFoundException;
import com.positivity.mcp.internal.exception.SessionOwnershipViolationException;
import com.positivity.mcp.internal.repository.NltiSessionRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantContextMissingException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Session lookups run bound to a tenant and never hand out another tenant's row (ADR-0062 plan WS6,
 * R-B6). Hibernate's tenant filter and row-level security keep such a row from ever reaching the
 * service; these tests pin the service-level contract on top: an unbound lookup fails loudly, a row
 * of another tenant is "not found" even if it did arrive, and not-found is distinct from
 * not-owned.
 */
@DisplayName("NltiSessionAccess (ADR-0062 WS6 session scoping)")
class NltiSessionAccessTest {

    private static final UUID SESSION_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");
    private static final String SUBJECT = "user-1";

    private NltiSessionRepository repository;
    private NltiSessionAccess access;

    @BeforeEach
    void setUp() {
        repository = mock(NltiSessionRepository.class);
        access = new NltiSessionAccess(repository);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("an unbound lookup fails before it reaches the repository")
    void unboundLookupFailsLoudly() {
        assertThatThrownBy(() -> access.findOwned(SESSION_ID, SUBJECT))
                .isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> access.findInTenant(SESSION_ID)).isInstanceOf(TenantContextMissingException.class);
        assertThatThrownBy(() -> access.requireOwned(SESSION_ID, SUBJECT))
                .isInstanceOf(TenantContextMissingException.class);
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("the bound tenant's own session is returned")
    void ownTenantRowIsReturned() {
        TenantContext.bind(TENANT_A);
        NltiSession session = session(TENANT_A, SUBJECT);
        when(repository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(session));
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session));

        assertThat(access.findOwned(SESSION_ID, SUBJECT)).contains(session);
        assertThat(access.findInTenant(SESSION_ID)).contains(session);
        assertThat(access.requireOwned(SESSION_ID, SUBJECT)).isSameAs(session);
    }

    @Test
    @DisplayName("a row of another tenant is treated as not found, whatever the repository answered")
    void otherTenantRowIsNotFound() {
        TenantContext.bind(TENANT_A);
        NltiSession foreign = session(TENANT_B, SUBJECT);
        when(repository.findByIdAndSubjectId(SESSION_ID, SUBJECT)).thenReturn(Optional.of(foreign));
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(foreign));

        assertThat(access.findOwned(SESSION_ID, SUBJECT)).isEmpty();
        assertThat(access.findInTenant(SESSION_ID)).isEmpty();
        assertThatThrownBy(() -> access.requireOwned(SESSION_ID, SUBJECT))
                .as("indistinguishable from an id that never existed: 404, not 403")
                .isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("requireOwned: no such session in this tenant is not-found")
    void requireOwnedAbsentIsNotFound() {
        TenantContext.bind(TENANT_A);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> access.requireOwned(SESSION_ID, SUBJECT)).isInstanceOf(SessionNotFoundException.class);
    }

    @Test
    @DisplayName("requireOwned: this tenant's session of another subject is an ownership violation")
    void requireOwnedOtherSubjectIsForbidden() {
        TenantContext.bind(TENANT_A);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(session(TENANT_A, "someone-else")));

        assertThatThrownBy(() -> access.requireOwned(SESSION_ID, SUBJECT))
                .isInstanceOf(SessionOwnershipViolationException.class);
    }

    @Test
    @DisplayName("a row not yet flushed (no tenant stamped) is the binding's own")
    void unstampedRowIsAccepted() {
        TenantContext.bind(TENANT_A);
        NltiSession fresh = session(null, SUBJECT);
        when(repository.findById(SESSION_ID)).thenReturn(Optional.of(fresh));

        assertThat(access.findInTenant(SESSION_ID)).contains(fresh);
    }

    /** Hibernate stamps tenant_id; the entity has no setter, so the test writes the field directly. */
    private static NltiSession session(UUID tenantId, String subjectId) {
        NltiSession session = new NltiSession();
        session.setId(SESSION_ID);
        session.setSubjectId(subjectId);
        if (tenantId != null) {
            ReflectionTestUtils.setField(session, "tenantId", tenantId);
        }
        return session;
    }
}
