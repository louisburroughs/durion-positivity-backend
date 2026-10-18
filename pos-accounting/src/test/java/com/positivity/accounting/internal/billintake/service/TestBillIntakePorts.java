package com.positivity.accounting.internal.billintake.service;

import static org.mockito.Mockito.mock;

import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.AccountingCalendarZoneResolver;
import com.positivity.accounting.internal.service.AccountingSequenceLocker;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.SupplierVendorCopies;
import com.positivity.accounting.internal.service.TestZoneResolvers;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import com.positivity.accounting.internal.service.VendorBillLocks;
import com.positivity.accounting.internal.service.VendorBillReader;
import com.positivity.accounting.internal.service.VendorBillStatedTax;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Hand-wires a {@link BillIntakePortImpl} for tests: every collaborator is a mock (or a fixed clock, a USD ledger and a
 * UTC calendar) until a test hands over its own with {@link Builder#with}, so a test names only the collaborators it
 * is about.
 */
public final class TestBillIntakePorts {

    private TestBillIntakePorts() {}

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private Clock clock = Clock.fixed(Instant.parse("2026-01-15T12:00:00Z"), ZoneOffset.UTC);
        private AccountingCalendarZoneResolver zoneResolver;
        private VendorBillRepository bills = mock(VendorBillRepository.class);
        private VendorBillLineRepository billLines = mock(VendorBillLineRepository.class);
        private BillIntakeItemRepository items = mock(BillIntakeItemRepository.class);
        private ExtSupplierVendorRepository vendorCopy = mock(ExtSupplierVendorRepository.class);
        private SupplierVendorCopies vendorCopies = mock(SupplierVendorCopies.class);
        private VendorBillDuplicateGuard guard = mock(VendorBillDuplicateGuard.class);
        private VendorBillLocks locks = mock(VendorBillLocks.class);
        private VendorBillReissueRepository reissues = mock(VendorBillReissueRepository.class);
        private VendorBillStatedTax statedTax = mock(VendorBillStatedTax.class);
        private VendorBillReader reader = mock(VendorBillReader.class);
        private LedgerCurrency ledgerCurrency = new LedgerCurrency("USD");
        private KafkaFactIngestionRecorder ingestionRecorder = mock(KafkaFactIngestionRecorder.class);
        private AccountingSequenceLocker sequenceLocker = mock(AccountingSequenceLocker.class);
        private ObjectProvider<MeterRegistry> meters = mock();
        private PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);

        /** Hands over a collaborator of the port, by its type. */
        public Builder with(Object collaborator) {
            switch (collaborator) {
                case Clock c -> clock = c;
                case AccountingCalendarZoneResolver z -> zoneResolver = z;
                case VendorBillRepository r -> bills = r;
                case VendorBillLineRepository r -> billLines = r;
                case BillIntakeItemRepository r -> items = r;
                case ExtSupplierVendorRepository r -> vendorCopy = r;
                case SupplierVendorCopies c -> vendorCopies = c;
                case VendorBillDuplicateGuard g -> guard = g;
                case VendorBillLocks l -> locks = l;
                case VendorBillReissueRepository r -> reissues = r;
                case VendorBillStatedTax t -> statedTax = t;
                case VendorBillReader r -> reader = r;
                case LedgerCurrency c -> ledgerCurrency = c;
                case KafkaFactIngestionRecorder r -> ingestionRecorder = r;
                case AccountingSequenceLocker l -> sequenceLocker = l;
                case PlatformTransactionManager m -> transactionManager = m;
                default -> throw new IllegalArgumentException("not a collaborator: " + collaborator.getClass());
            }
            return this;
        }

        /** The meter registry the port finds, when a test asserts a metric. */
        public Builder withMeters(ObjectProvider<MeterRegistry> provider) {
            this.meters = provider;
            return this;
        }

        public BillIntakePortImpl build() {
            return new BillIntakePortImpl(
                    clock,
                    bills,
                    billLines,
                    items,
                    vendorCopy,
                    vendorCopies,
                    guard,
                    locks,
                    reissues,
                    statedTax,
                    reader,
                    ledgerCurrency,
                    ingestionRecorder,
                    sequenceLocker,
                    zoneResolver != null ? zoneResolver : TestZoneResolvers.utc(clock),
                    meters,
                    transactionManager);
        }
    }
}
