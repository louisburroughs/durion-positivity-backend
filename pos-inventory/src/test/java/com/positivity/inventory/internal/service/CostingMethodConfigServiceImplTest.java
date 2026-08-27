package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.inventory.internal.dto.costing.CostingMethodConfigRequest;
import com.positivity.inventory.internal.dto.costing.CostingMethodConfigResponse;
import com.positivity.inventory.internal.entity.CostMethodChangeLog;
import com.positivity.inventory.internal.enums.CostMethodChangeType;
import com.positivity.inventory.internal.enums.CostingMethod;
import com.positivity.inventory.internal.enums.CostingScopeType;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
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
        assertThat(logs.getFirst().getChangeType()).isEqualTo(CostMethodChangeType.METHOD_SET);
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

    // ─── SKU_CATEGORY scope (#1535) ─────────────────────────────────────────

    @Test
    @DisplayName("a SKU_CATEGORY row persists and stays inert while the provider is the no-op")
    void skuCategoryScope_upsertPersistsTheRowAndStaysInertWhileTheProviderIsNoOp() {
        String category = "Category-" + java.util.UUID.randomUUID();
        String sku = java.util.UUID.randomUUID().toString();

        CostingMethodConfigResponse response = service.upsertConfig(CostingMethodConfigRequest.builder()
                .scopeType(CostingScopeType.SKU_CATEGORY)
                .scopeValue(category)
                .method(CostingMethod.STANDARD)
                .build());

        assertThat(response.getConfigId()).isNotNull();
        assertThat(response.getScopeType()).isEqualTo(CostingScopeType.SKU_CATEGORY);
        assertThat(response.isActive()).isTrue();

        // pos.inventory.sku-category.resolve-from-replica is false under the test profile, so the SPI
        // is NoOpSkuCategoryProvider and the row it just stored decides nothing.
        assertThat(resolver.resolve(sku)).isEqualTo(CostingMethod.AVERAGE);
    }

    @Test
    @DisplayName("SKU_CATEGORY scope rejects a blank scope value")
    void skuCategoryScope_blankScopeValue_throws() {
        assertThatThrownBy(() -> service.upsertConfig(CostingMethodConfigRequest.builder()
                        .scopeType(CostingScopeType.SKU_CATEGORY)
                        .scopeValue("   ")
                        .method(CostingMethod.STANDARD)
                        .build()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ─── deactivate (#1535) ─────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate soft-deletes and writes a DEACTIVATED row with a null toMethod")
    void deactivateConfig_softDeletesAndWritesDeactivatedRowWithNullToMethod() {
        String sku = "SKU-" + java.util.UUID.randomUUID();
        CostingMethodConfigResponse created = service.upsertConfig(request(sku, CostingMethod.STANDARD));

        CostingMethodConfigResponse deactivated = service.deactivateConfig(created.getConfigId());

        assertThat(deactivated.isActive()).isFalse();
        assertThat(deactivated.getMethod()).isEqualTo(CostingMethod.STANDARD);

        List<CostMethodChangeLog> logs = logsFor(sku);
        assertThat(logs).hasSize(2);
        assertThat(logs)
                .filteredOn(l -> l.getChangeType() == CostMethodChangeType.DEACTIVATED)
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getFromMethod()).isEqualTo(CostingMethod.STANDARD);
                    assertThat(l.getToMethod()).isNull();
                    assertThat(l.getChangedBy()).isNotBlank();
                });
    }

    @Test
    @DisplayName("deactivating an already inactive row returns it and writes no second log row")
    void deactivateConfig_alreadyInactive_returnsRowAndWritesNoSecondLogRow() {
        String sku = "SKU-" + java.util.UUID.randomUUID();
        CostingMethodConfigResponse created = service.upsertConfig(request(sku, CostingMethod.STANDARD));
        service.deactivateConfig(created.getConfigId());

        CostingMethodConfigResponse again = service.deactivateConfig(created.getConfigId());

        assertThat(again.isActive()).isFalse();
        // An append-only audit must not be pollutable by a repeated DELETE.
        assertThat(logsFor(sku))
                .filteredOn(l -> l.getChangeType() == CostMethodChangeType.DEACTIVATED)
                .hasSize(1);
    }

    @Test
    @DisplayName("deactivating an unknown id throws ResourceNotFoundException")
    void deactivateConfig_unknownId_throwsResourceNotFound() {
        assertThatThrownBy(() -> service.deactivateConfig(java.util.UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("reactivating at the same method records a REACTIVATED row")
    void reactivatingWithTheSameMethod_writesReactivatedChangeLogRow() {
        String sku = "SKU-" + java.util.UUID.randomUUID();
        CostingMethodConfigResponse created = service.upsertConfig(request(sku, CostingMethod.STANDARD));
        service.deactivateConfig(created.getConfigId());

        CostingMethodConfigResponse revived = service.upsertConfig(request(sku, CostingMethod.STANDARD));

        assertThat(revived.isActive()).isTrue();
        // Before #1535 this round trip was entirely invisible: the method did not change on the way
        // back, so the upsert wrote nothing at all.
        assertThat(logsFor(sku))
                .filteredOn(l -> l.getChangeType() == CostMethodChangeType.REACTIVATED)
                .singleElement()
                .satisfies(l -> {
                    assertThat(l.getFromMethod()).isEqualTo(CostingMethod.STANDARD);
                    assertThat(l.getToMethod()).isEqualTo(CostingMethod.STANDARD);
                });
    }

    @Test
    @DisplayName("the first upsert on a new row records METHOD_SET, not REACTIVATED")
    void firstUpsertOnANewRow_recordsMethodSetNotReactivated() {
        String sku = "SKU-" + java.util.UUID.randomUUID();

        service.upsertConfig(request(sku, CostingMethod.STANDARD));

        // The trap: a brand-new builder-made row is also inactive (no @Builder.Default on `active`),
        // so "was inactive" alone cannot tell creation from reactivation. Branch order is what does.
        assertThat(logsFor(sku)).singleElement().satisfies(l -> {
            assertThat(l.getChangeType()).isEqualTo(CostMethodChangeType.METHOD_SET);
            assertThat(l.getFromMethod()).isNull();
            assertThat(l.getToMethod()).isEqualTo(CostingMethod.STANDARD);
        });
    }

    private List<CostMethodChangeLog> logsFor(String scopeValue) {
        return changeLogRepository.findAll().stream()
                .filter(l -> scopeValue.equals(l.getScopeValue()))
                .toList();
    }

    private CostingMethodConfigRequest request(String sku, CostingMethod method) {
        return CostingMethodConfigRequest.builder()
                .scopeType(CostingScopeType.SKU)
                .scopeValue(sku)
                .method(method)
                .build();
    }
}
