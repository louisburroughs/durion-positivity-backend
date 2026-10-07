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
import com.positivity.domainevents.inventory.InventoryAdjustedV1;
import com.positivity.domainevents.inventory.ProductValueChangedV1;
import com.positivity.domainevents.inventory.ScrapPostedV1;
import com.positivity.domainevents.invoice.InvoiceUpdatedV1;
import com.positivity.domainevents.order.RegisterSessionClosedV1;
import com.positivity.domainevents.payment.PaymentSettledV1;
import com.positivity.domainevents.supplier.SupplierInvoiceReceivedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementResolvedV1;
import com.positivity.domainevents.warranty.WarrantyReimbursementSubmittedV1;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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

    /**
     * The concrete domain-event constants each listener dispatches on and records an {@code
     * accounting_event} for, written out independently of the registry so that dropping a code from
     * the registry, or adding one no listener records, fails here.
     */
    static Stream<Arguments> listenerDispatchedTypes() {
        return Stream.of(
                Arguments.of(
                        "invoice", InvoiceEventsListener.RECORDED_EVENT_TYPES, List.of(InvoiceUpdatedV1.EVENT_TYPE)),
                Arguments.of(
                        "order", OrderEventsListener.RECORDED_EVENT_TYPES, List.of(RegisterSessionClosedV1.EVENT_TYPE)),
                Arguments.of(
                        "inventory",
                        InventoryEventsListener.RECORDED_EVENT_TYPES,
                        List.of(
                                ScrapPostedV1.EVENT_TYPE,
                                InventoryAdjustedV1.EVENT_TYPE,
                                ProductValueChangedV1.EVENT_TYPE)),
                Arguments.of(
                        "supplier",
                        SupplierInvoiceEventsListener.RECORDED_EVENT_TYPES,
                        List.of(SupplierInvoiceReceivedV1.EVENT_TYPE)),
                Arguments.of(
                        "warranty",
                        WarrantyEventsListener.RECORDED_EVENT_TYPES,
                        List.of(
                                WarrantyReimbursementSubmittedV1.EVENT_TYPE,
                                WarrantyReimbursementResolvedV1.EVENT_TYPE)),
                Arguments.of(
                        "payment",
                        SettlementEventsListener.RECORDED_EVENT_TYPES,
                        List.of(PaymentSettledV1.EVENT_TYPE)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("listenerDispatchedTypes")
    @DisplayName("each listener records exactly the KAFKA codes it dispatches on")
    void listenerCodesRegistered(String sourceDomain, List<String> recorded, List<String> dispatched) {
        assertThat(AccountingEventTypeRegistry.kafkaCodes(sourceDomain))
                .containsExactlyInAnyOrderElementsOf(dispatched);
        assertThat(recorded).containsExactlyInAnyOrderElementsOf(dispatched);
    }

    @Test
    @DisplayName("the KAFKA codes are exactly the listener-dispatched constants")
    void kafkaCodesAreExactlyTheDispatchedOnes() {
        List<String> dispatched = listenerDispatchedTypes()
                .map(a -> (List<?>) a.get()[2])
                .flatMap(List::stream)
                .map(String.class::cast)
                .toList();

        assertThat(kafkaCodes()).containsExactlyInAnyOrderElementsOf(dispatched);
    }

    @Test
    @DisplayName("the API codes are exactly the types the module's own code submits")
    void apiCodesAreTheCodeSubmittedTypes() {
        assertThat(AccountingEventTypeRegistry.entries())
                .filteredOn(e -> e.ingestion() == Ingestion.API)
                .extracting(Entry::code)
                .containsExactlyInAnyOrder("INVOICE_PAYMENT", "AP_PAYMENT_GL_POSTING");
    }

    @Test
    @DisplayName("the AP payment API type posts a journal entry through the posting engine; VENDOR_BILL_GL_POSTING is"
            + " retired (#2509): a vendor bill posts at approval")
    void apGlPostingTypesPostToGl() {
        assertThat(AccountingEventTypeRegistry.entries())
                .filteredOn(e -> e.code().endsWith("_GL_POSTING"))
                .hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.ingestion()).isEqualTo(Ingestion.API);
                    assertThat(e.sourceDomain()).isEqualTo("accounting");
                    assertThat(e.postsToGl()).isTrue();
                });
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
