package com.positivity.supplier.internal.vendor.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.domainevents.supplier.SupplierVendorUpdatedV1;
import com.positivity.supplier.PostgresSliceTestBase;
import com.positivity.supplier.internal.config.JpaConfig;
import com.positivity.supplier.internal.entity.SupplierVendorEntity;
import com.positivity.supplier.internal.repository.SupplierOutboxEventRepository;
import com.positivity.supplier.internal.repository.SupplierVendorRepository;
import com.positivity.supplier.internal.service.SupplierOutboxEventWriter;
import com.positivity.supplier.internal.vendor.service.model.VendorCreateRequest;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A vendor change and its fact commit or roll back together (#2516, ADR-0044 §4): the fact is queued
 * in the transaction that commits the state, and cannot be queued outside one.
 *
 * <p>{@code Propagation.NOT_SUPPORTED}: a test wrapped in its own transaction would hide whether the
 * work under test committed.
 */
@Import({
    JpaConfig.class,
    SupplierVendorServiceImpl.class,
    VendorNumberAllocator.class,
    VendorFactPublisher.class,
    SupplierOutboxEventWriter.class,
    SupplierVendorFactTransactionTest.SupportConfig.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DisplayName("Vendor facts are transactional with the vendor (#2516)")
class SupplierVendorFactTransactionTest extends PostgresSliceTestBase {

    @TestConfiguration
    static class SupportConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }

        @Bean
        ObjectMapper objectMapper() {
            return JsonMapper.builder().build();
        }
    }

    @Autowired
    private SupplierVendorService service;

    @Autowired
    private VendorFactPublisher publisher;

    @Autowired
    private SupplierVendorRepository vendorRepository;

    @Autowired
    private SupplierOutboxEventRepository outboxRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private long vendorFactRows() {
        return outboxRepository.findAll().stream()
                .filter(row -> SupplierVendorUpdatedV1.EVENT_TYPE.equals(row.getEventType()))
                .count();
    }

    @Test
    @DisplayName("a rolled-back create leaves neither the vendor nor its fact")
    void rollbackTakesTheFactWithIt() {
        long vendorsBefore = vendorRepository.count();
        long factsBefore = vendorFactRows();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.createVendor(new VendorCreateRequest(
                    "ROLLED-BACK", "Rolled Back Ltd", "Rolled Back", null, null, "NET30", "USD"));
            status.setRollbackOnly();
        });

        assertThat(vendorRepository.count()).isEqualTo(vendorsBefore);
        assertThat(vendorFactRows()).isEqualTo(factsBefore);
    }

    @Test
    @DisplayName("a fact cannot be queued outside the transaction that commits the vendor")
    void publishingOutsideATransactionIsRefused() {
        SupplierVendorEntity vendor = SupplierVendorEntity.builder()
                .vendorId(java.util.UUID.randomUUID())
                .vendorNumber("NO-TX")
                .legalName("No Tx")
                .displayName("No Tx")
                .status(com.positivity.supplier.internal.enums.VendorStatus.ACTIVE)
                .version(0L)
                .createdBy("clerk.a")
                .createdAt(Instant.now())
                .build();

        assertThatThrownBy(() -> publisher.publish(vendor, Instant.now(), "clerk.a"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
