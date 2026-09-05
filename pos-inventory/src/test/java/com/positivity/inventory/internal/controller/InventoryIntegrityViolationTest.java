package com.positivity.inventory.internal.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.positivity.inventory.config.TestSecurityConfig;
import com.positivity.inventory.internal.config.SkuCategoryCutoverService;
import com.positivity.inventory.internal.costing.service.CostingMethodConfigService;
import com.positivity.web.common.GlobalApiExceptionHandler;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Issue #1768: a unique-constraint collision in this module must answer 409 DUPLICATE_RESOURCE,
 * as ADR-0056 §2 requires — not the 500 INTERNAL_ERROR it answered until the module-local
 * catch-all was removed.
 *
 * <p>The defect was invisible to the #1717 enforcement because it was not a shape problem.
 * {@code InventoryGlobalExceptionHandler} declared {@code @ExceptionHandler(Exception.class)} and
 * returned a correctly shaped {@link com.positivity.shared.error.ApiError}, so the envelope rule
 * passed it. But Spring's {@code ExceptionHandlerExceptionResolver} selects the first applicable
 * advice bean that has ANY matching method rather than the most specific handler across advices,
 * so that catch-all ran instead of pos-web-common's {@link GlobalApiExceptionHandler} — and this
 * module's advice never mapped {@code DataIntegrityViolationException} at all. The classification
 * was simply gone, behind a well-formed envelope.
 *
 * <p>Both advices are imported here on purpose. The point of the test is which one wins when both
 * are present, so loading only one would assert nothing about the precedence that caused the bug.
 *
 * <p>The exception is thrown from a stubbed service rather than a real constraint: several
 * pos-inventory services catch {@code DataIntegrityViolationException} locally for race handling
 * (PutawayRuleServiceImpl, InventoryLotCaptureService, LedgerPostingServiceImpl,
 * SkuCostStateInitializer), so driving a genuine collision would exercise those catches instead of
 * the advice chain this test is about.
 */
@WebMvcTest(CostingMethodController.class)
@Import({TestSecurityConfig.class, InventoryIntegrityViolationTest.Advices.class})
@ActiveProfiles("test")
@DisplayName("ADR-0056 §2: an integrity violation is a 409, not a 500")
class InventoryIntegrityViolationTest {

    private static final String ADMIN = "inventory:location:admin";
    private static final String BASE = "/v1/inventory/valuation/methods";

    /**
     * A real fixed Clock, not a mock. Both advices stamp {@code Instant.now(clock)} onto the
     * envelope; an unstubbed mock returns null there, the advice itself throws, and Spring
     * rethrows the ORIGINAL exception — which would make this test fail as if the mapping were
     * missing when only the stub was.
     */
    @TestConfiguration
    static class Advices {
        @Bean
        Clock clock() {
            return Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
        }

        // Declaration order matters and is deliberate. Both advices sit at
        // Ordered.LOWEST_PRECEDENCE — the platform one by annotation, the module one by the
        // default — so AnnotationAwareOrderComparator leaves them in registration order, and
        // ExceptionHandlerExceptionResolver takes the FIRST advice with any matching method.
        // In the running application the module advice is component-scanned and the platform
        // advice arrives from auto-configuration, which registers after user beans; the module
        // advice therefore comes first. Declaring it first here reproduces that. Reversing these
        // two lines makes this test pass even with a module-local catch-all present, which is
        // exactly the false green it exists to avoid.
        @Bean
        InventoryGlobalExceptionHandler inventoryGlobalExceptionHandler(Clock clock) {
            return new InventoryGlobalExceptionHandler(clock);
        }

        @Bean
        GlobalApiExceptionHandler globalApiExceptionHandler(Clock clock) {
            return new GlobalApiExceptionHandler(clock);
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CostingMethodConfigService costingMethodConfigService;

    @MockitoBean
    SkuCategoryCutoverService skuCategoryCutoverService;

    @Test
    void aDuplicateKeyAnswers409DuplicateResource() throws Exception {
        // Shaped like a real PostgreSQL unique violation: the classifier walks the cause chain for
        // a SQLException and reads its SQLState (23505 = unique_violation). A DataIntegrityViolation
        // with no such cause classifies as UNKNOWN and stays a 500, so a cause-less stub would pass
        // for the wrong reason once the shadowing is fixed.
        SQLException unique = new SQLException(
                "ERROR: duplicate key value violates unique constraint \"uk_costing_method_scope\"", "23505");
        when(costingMethodConfigService.listConfigs())
                .thenThrow(new DataIntegrityViolationException("could not execute statement", unique));

        mockMvc.perform(get(BASE).header("X-Authorities", ADMIN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_RESOURCE"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.correlationId").exists());
    }
}
