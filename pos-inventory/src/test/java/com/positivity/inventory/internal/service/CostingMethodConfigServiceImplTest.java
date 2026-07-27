package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.internal.entity.CostMethodChangeLog;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.repository.CostMethodChangeLogRepository;
import com.positivity.inventory.service.CostingMethodConfigService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/** Admin upsert + change-log recording for costing-method config (odoo-parity J1, issue #1048). */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("CostingMethodConfigService admin + change log")
class CostingMethodConfigServiceImplTest {

    @Autowired
    private CostingMethodConfigService service;

    @Autowired
    private CostMethodChangeLogRepository changeLogRepository;

    @Autowired
    private CostingMethodResolver resolver;

    @Test
    @DisplayName("first upsert creates the config and records a from=null change-log row")
    void firstUpsert_recordsChangeLog() {
        String sku = "SKU-" + java.util.UUID.randomUUID();
        CostingMethodConfigResponse response = service.upsertConfig(CostingMethodConfigRequest.builder()
                .scopeType(CostingScopeType.SKU)
                .scopeValue(sku)
                .method(CostingMethod.STANDARD)
                .build());

        assertThat(response.getConfigId()).isNotNull();
        assertThat(response.isActive()).isTrue();
        assertThat(response.getMethod()).isEqualTo(CostingMethod.STANDARD);

        // resolver now returns the configured method for that SKU
        assertThat(resolver.resolve(sku)).isEqualTo(CostingMethod.STANDARD);

        List<CostMethodChangeLog> logs = changeLogRepository.findAll().stream()
                .filter(l -> sku.equals(l.getScopeValue()))
                .toList();
        assertThat(logs).hasSize(1);
        assertThat(logs.getFirst().getFromMethod()).isNull();
        assertThat(logs.getFirst().getToMethod()).isEqualTo(CostingMethod.STANDARD);
        assertThat(logs.getFirst().getChangedBy()).isNotBlank();
    }

    @Test
    @DisplayName("changing the method records from/to; re-upserting the same method does not")
    void methodChange_recordsFromTo_idempotentUpsertDoesNot() {
        String sku = "SKU-" + java.util.UUID.randomUUID();
        service.upsertConfig(request(sku, CostingMethod.AVERAGE));
        service.upsertConfig(request(sku, CostingMethod.AVERAGE)); // no change -> no new log
        service.upsertConfig(request(sku, CostingMethod.STANDARD)); // change -> new log

        List<CostMethodChangeLog> logs = changeLogRepository.findAll().stream()
                .filter(l -> sku.equals(l.getScopeValue()))
                .toList();
        assertThat(logs).hasSize(2);
        assertThat(logs).anyMatch(l -> l.getFromMethod() == null && l.getToMethod() == CostingMethod.AVERAGE);
        assertThat(logs)
                .anyMatch(l -> l.getFromMethod() == CostingMethod.AVERAGE && l.getToMethod() == CostingMethod.STANDARD);
    }

    @Test
    @DisplayName("DEFAULT scope rejects a scope value")
    void defaultScope_rejectsScopeValue() {
        assertThatThrownBy(() -> service.upsertConfig(CostingMethodConfigRequest.builder()
                        .scopeType(CostingScopeType.DEFAULT)
                        .scopeValue("nope")
                        .method(CostingMethod.AVERAGE)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private CostingMethodConfigRequest request(String sku, CostingMethod method) {
        return CostingMethodConfigRequest.builder()
                .scopeType(CostingScopeType.SKU)
                .scopeValue(sku)
                .method(method)
                .build();
    }
}
