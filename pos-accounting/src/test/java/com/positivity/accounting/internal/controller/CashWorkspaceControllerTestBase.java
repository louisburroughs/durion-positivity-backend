package com.positivity.accounting.internal.controller;

import com.positivity.accounting.BaseIntegrationTest;
import com.positivity.accounting.internal.service.CashOutlookService;
import com.positivity.accounting.internal.service.CashPositionService;
import com.positivity.accounting.internal.service.WorkItemAutomaticService;
import com.positivity.accounting.internal.service.WorkItemService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The mocked services of the cash workspace controllers (CAP:550 S19, #2515): one set of {@code @MockitoBean}s for the
 * cash position, cash outlook and work-item controller tests, so the three share one cached application context
 * instead of building three (each holds the whole Hibernate model, and the CI fork's heap is small).
 */
abstract class CashWorkspaceControllerTestBase extends BaseIntegrationTest {

    @MockitoBean
    protected CashPositionService cashPositionService;

    @MockitoBean
    protected CashOutlookService cashOutlookService;

    @MockitoBean
    protected WorkItemService workItemService;

    @MockitoBean
    protected WorkItemAutomaticService workItemAutomaticService;
}
