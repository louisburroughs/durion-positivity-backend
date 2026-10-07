package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.PettyExpenseCategory;
import com.positivity.accounting.internal.entity.RegisterFloat;
import com.positivity.accounting.internal.repository.PettyExpenseCategoryRepository;
import com.positivity.accounting.internal.repository.RegisterFloatChangeRepository;
import com.positivity.accounting.internal.repository.RegisterFloatRepository;
import com.positivity.tenancy.TenantIterator;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Re-emits the current state of every petty-expense category and register float at each start, per tenant
 * (#2511 PROPOSED 11; ADR-0044 §4 "re-emit of current state"). Consumers keep a copy (pos-order's picker and
 * opening float, S16) and ignore versions they already hold, so a fact lost before this start is healed
 * without a replay request. A float's fact states its currency (#2577; ADR-0067 R-1). A category the template sweep creates during this start queues its own fact, so
 * the order of the two runners does not matter.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "pos.accounting.cash-setup.bootstrap-republish.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CashSetupFactsBootstrap implements ApplicationRunner {

    /** The actor the republished facts carry. */
    static final String ACTOR = "SYSTEM";

    private final TenantIterator tenantIterator;
    private final PettyExpenseCategoryRepository categories;
    private final RegisterFloatRepository floats;
    private final RegisterFloatChangeRepository floatChanges;
    private final PettyExpenseCategoryFacts categoryFacts;
    private final RegisterFloatFacts floatFacts;
    private final TransactionTemplate transaction;

    public CashSetupFactsBootstrap(
            TenantIterator tenantIterator,
            PettyExpenseCategoryRepository categories,
            RegisterFloatRepository floats,
            RegisterFloatChangeRepository floatChanges,
            PettyExpenseCategoryFacts categoryFacts,
            RegisterFloatFacts floatFacts,
            PlatformTransactionManager transactionManager) {
        this.tenantIterator = tenantIterator;
        this.categories = categories;
        this.floats = floats;
        this.floatChanges = floatChanges;
        this.categoryFacts = categoryFacts;
        this.floatFacts = floatFacts;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            republishAll();
        } catch (RuntimeException e) {
            log.error("Petty-expense category and register float republish failed; continuing startup", e);
        }
    }

    /**
     * Republishes every tenant's categories and floats, each tenant in its own transaction.
     *
     * @return the number of facts queued
     */
    public int republishAll() {
        AtomicInteger queued = new AtomicInteger();
        int tenants = tenantIterator.forEachActiveTenant(tenantId -> {
            Integer count = transaction.execute(status -> republishBoundTenant());
            queued.addAndGet(count == null ? 0 : count);
        });
        log.info("Republished {} petty-expense category and register float fact(s) for {} tenant(s)", queued, tenants);
        return queued.get();
    }

    /** Queues the current state of the bound tenant's categories and floats; joins the caller's transaction. */
    public int republishBoundTenant() {
        int queued = 0;
        for (PettyExpenseCategory category : categories.findAllByOrderByCodeAsc()) {
            categoryFacts.changed(category, ACTOR);
            queued++;
        }
        for (RegisterFloat registerFloat : floats.findAllByOrderByRegisterIdAsc()) {
            var latest = floatChanges.findFirstByRegisterFloatIdOrderByCreatedAtDescChangeIdDesc(
                    registerFloat.getRegisterFloatId());
            if (latest.isEmpty()) {
                continue;
            }
            floatFacts.changed(
                    registerFloat,
                    RegisterFloatFacts.factOf(registerFloat, latest.get(), registerFloat.getAmount(), null),
                    ACTOR);
            queued++;
        }
        return queued;
    }
}
