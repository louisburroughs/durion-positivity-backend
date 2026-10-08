package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.repository.ReceivablePaymentRepository;
import com.positivity.tenancy.TenantContext;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.dialect.PostgreSQLDialect;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serializes the AP approval policy PUTs of one tenant (CAP:550 S13, #2510): the policy's rows exist only once a
 * setting is first written, so on a fresh tenant a row lock has nothing to hold, and two first PUTs would both pass
 * the replay check and race on the {@code (tenant_id, config_key)} inserts. The PUT takes this lock first.
 *
 * <p>A Postgres transaction-scoped advisory lock keyed on the tenant ({@link PaymentIntentLock}'s mechanism): no row,
 * no second connection, released at commit or rollback. On any other database (the H2 {@code test} profile) it does
 * nothing; those tests run one thread.
 */
@Slf4j
@Component
public class ApApprovalPolicyLock {

    private static final String SCOPE = "AP_APPROVAL_POLICY:";

    private final ReceivablePaymentRepository advisoryLocks;
    private final boolean postgres;

    public ApApprovalPolicyLock(ReceivablePaymentRepository advisoryLocks, EntityManagerFactory entityManagerFactory) {
        this.advisoryLocks = advisoryLocks;
        this.postgres = entityManagerFactory
                        .unwrap(SessionFactoryImplementor.class)
                        .getJdbcServices()
                        .getDialect()
                instanceof PostgreSQLDialect;
        if (!postgres) {
            log.info("AP approval policy lock disabled: the database is not Postgres");
        }
    }

    /** Wait for, then hold until this transaction ends, the current tenant's policy lock. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lock() {
        if (postgres) {
            advisoryLocks.takeAdvisoryTransactionLock(key(TenantContext.require()));
        }
    }

    /** The tenant's lock key: a name-based UUID of the scope and tenant, folded into one 64-bit value. */
    static long key(UUID tenantId) {
        UUID scoped = UUID.nameUUIDFromBytes((SCOPE + tenantId).getBytes(StandardCharsets.UTF_8));
        return scoped.getMostSignificantBits() ^ scoped.getLeastSignificantBits();
    }
}
