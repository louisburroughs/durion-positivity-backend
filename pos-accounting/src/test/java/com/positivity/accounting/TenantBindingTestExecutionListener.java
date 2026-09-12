package com.positivity.accounting;

import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.testing.TenantTestSupport;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Binds {@link TenantTestSupport#TENANT_A} around every test method of a {@link
 * PostgresIntegrationTestBase} subclass.
 *
 * <p>A listener rather than a {@code @BeforeEach}, for two reasons a JUnit callback cannot meet:
 *
 * <ul>
 *   <li><strong>It has to happen before the transaction.</strong> A transactional test opens its
 *       transaction before any {@code @BeforeEach} runs, and {@code TenantAwareDataSource} binds the
 *       tenant onto a connection at checkout — so a tenant bound afterwards never reaches the
 *       connection the whole test then uses, and row-level security rejects every write. This
 *       listener is ordered ahead of {@code TransactionalTestExecutionListener} (4000).
 *   <li><strong>{@code @Nested} classes.</strong> Spring resolves listeners through the enclosing
 *       class, so a nested class inherits this one; {@code @BeforeTransaction} is looked up on the
 *       nested class's own hierarchy, which does not include the enclosing test's base class.
 * </ul>
 *
 * <p>Clearing is left to {@code afterTestMethod}, which runs after the transaction listener has
 * rolled back and after every {@code @AfterEach} — so a class that cleans up committed rows over a
 * raw connection still has its tenant bound while it does.
 */
public class TenantBindingTestExecutionListener extends AbstractTestExecutionListener {

    /** Between {@code DirtiesContextTestExecutionListener} (3000) and the transactional one (4000). */
    @Override
    public int getOrder() {
        return 3500;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        TenantContext.bind(TenantTestSupport.TENANT_A);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        TenantContext.clear();
    }
}
