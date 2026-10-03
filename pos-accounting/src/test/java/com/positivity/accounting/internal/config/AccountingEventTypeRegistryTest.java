package com.positivity.accounting.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.accounting.internal.config.AccountingEventTypeRegistry.Entry;
import com.positivity.accounting.internal.config.AccountingEventTypeRegistry.Ingestion;
import com.positivity.accounting.internal.service.InventoryEventsListener;
import com.positivity.accounting.internal.service.InvoiceEventsListener;
import com.positivity.accounting.internal.service.InvoicePaymentEventProcessor;
import com.positivity.accounting.internal.service.OrderEventsListener;
import com.positivity.accounting.internal.service.SettlementEventsListener;
import com.positivity.accounting.internal.service.SupplierInvoiceEventsListener;
import com.positivity.accounting.internal.service.WarrantyEventsListener;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AccountingEventTypeRegistry")
class AccountingEventTypeRegistryTest {

    private static List<String> codes() {
        return AccountingEventTypeRegistry.entries().stream().map(Entry::code).toList();
    }

    @Test
    @DisplayName("codes are unique")
    void codesAreUnique() {
        assertThat(codes()).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("every listener-recorded code is registered as KAFKA")
    void listenerCodesRegistered() {
        List<String> recorded = Stream.of(
                        InvoiceEventsListener.RECORDED_EVENT_TYPES,
                        OrderEventsListener.RECORDED_EVENT_TYPES,
                        SupplierInvoiceEventsListener.RECORDED_EVENT_TYPES,
                        WarrantyEventsListener.RECORDED_EVENT_TYPES,
                        InventoryEventsListener.RECORDED_EVENT_TYPES,
                        SettlementEventsListener.RECORDED_EVENT_TYPES)
                .flatMap(List::stream)
                .toList();

        assertThat(recorded).isNotEmpty().doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(kafkaCodes());
    }

    @Test
    @DisplayName("INVOICE_PAYMENT is the registered API type and posts no journal entry")
    void invoicePaymentRegistered() {
        assertThat(InvoicePaymentEventProcessor.EVENT_TYPE).isEqualTo("INVOICE_PAYMENT");
        assertThat(AccountingEventTypeRegistry.entries())
                .filteredOn(e -> e.code().equals(InvoicePaymentEventProcessor.EVENT_TYPE))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.ingestion()).isEqualTo(Ingestion.API);
                    assertThat(e.postsToGl()).isFalse();
                });
    }

    @Test
    @DisplayName("postsToGl is false for vendor bill, warranty and settled-payment types")
    void postsToGl() {
        assertThat(AccountingEventTypeRegistry.entries())
                .filteredOn(e -> !e.postsToGl())
                .extracting(Entry::sourceDomain)
                .containsOnly("supplier", "warranty", "payment");
    }

    private static List<String> kafkaCodes() {
        return AccountingEventTypeRegistry.entries().stream()
                .filter(e -> e.ingestion() == Ingestion.KAFKA)
                .map(Entry::code)
                .toList();
    }
}
