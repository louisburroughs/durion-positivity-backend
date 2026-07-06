package com.positivity.mcp.internal.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.mcp.internal.entity.NltiSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Gate 2C: workflow-state enum + session default. */
class WorkflowStateTest {

    @Test
    @DisplayName("workflow states include the non-IDLE operational sets; default is IDLE")
    void enumValues() {
        assertThat(WorkflowState.values())
                .contains(
                        WorkflowState.IDLE,
                        WorkflowState.CREATING_PO,
                        WorkflowState.RECEIVING_ASN,
                        WorkflowState.INVENTORY_RECON,
                        WorkflowState.PROCESSING_RETURN);
        assertThat(WorkflowState.DEFAULT).isEqualTo(WorkflowState.IDLE);
    }

    @Test
    @DisplayName("a new NltiSession defaults to IDLE workflow state")
    void sessionDefaultsToIdle() {
        assertThat(new NltiSession().getWorkflowState()).isEqualTo(WorkflowState.IDLE);
    }
}
