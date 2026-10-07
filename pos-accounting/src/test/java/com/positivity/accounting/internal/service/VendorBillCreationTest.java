package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.entity.AccountingSequence;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Unit tests of goods-receipt bill creation in VendorBillServiceImpl: the per-tenant bill number, the vendor
 * directory, and (AW37, #2509) that creation posts nothing: the bill posts once, at approval.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("VendorBillService - goods-receipt bill creation")
class VendorBillCreationTest {

    private static final Clock FIXED_CLOCK =
            Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"), java.time.ZoneOffset.UTC);

    @Spy
    Clock clock = FIXED_CLOCK;

    @Spy
    AccountingCalendarZoneResolver zoneResolver = TestZoneResolvers.utc(FIXED_CLOCK);

    @Mock
    private VendorBillRepository billRepository;

    @Mock
    private VendorBillLineRepository billLineRepository;

    @Mock
    private VendorBillReader reader;

    @Mock
    private AccountingAuditLogRepository auditLogs;

    @Mock
    private VendorBillInvoiceMatcher matcher;

    @Mock
    private VendorDirectoryService vendorDirectoryService;

    /** A mock answers "no duplicate": these tests are about creation, not the rule (#2501). */
    @Mock
    private VendorBillDuplicateGuard duplicateGuard;

    /** The goods-receipt create runs in a TransactionTemplate (#2501); a mock manager just runs it. */
    @Mock
    private PlatformTransactionManager transactionManager;

    /** The per-tenant counter goods-receipt bill numbers are drawn from (ADR-0062 section 9). */
    @Mock
    private AccountingSequenceLocker sequenceLocker;

    /** The tenant's counter for the fixed clock's month, 2024-01, about to hand out 42. */
    private final AccountingSequence billCounter = new AccountingSequence();

    @InjectMocks
    private VendorBillServiceImpl vendorBillService;

    private GoodsReceivedEvent testEvent;
    private UUID testVendorId;
    private UUID testPoId;
    private UUID testProductId1;
    private UUID testProductId2;

    @BeforeEach
    void setUp() {
        billCounter.setScopeKey("BILL-202401");
        billCounter.setNextValue(42L);
        // Lenient: the replayed-event test returns the existing bill before any number is drawn.
        lenient().when(sequenceLocker.lockOrProvision("BILL-202401")).thenReturn(billCounter);
        testVendorId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        testPoId = UUID.fromString("00000000-0000-0000-0000-000000000009");
        testProductId1 = UUID.fromString("00000000-0000-0000-0000-000000000011");
        testProductId2 = UUID.fromString("00000000-0000-0000-0000-000000000021");

        testEvent = GoodsReceivedEvent.builder()
                .eventId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
                .organizationId(UUID.fromString("00000000-0000-0000-0000-000000000041"))
                .purchaseOrderId(testPoId)
                .vendorId(testVendorId)
                .vendorName("Test Vendor Inc")
                .receivedDate(LocalDateTime.of(2026, 2, 11, 10, 30))
                .lineItems(List.of(
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId1)
                                .description("Widget Type A")
                                .quantity(new BigDecimal("100.00"))
                                .unitPrice(new BigDecimal("12.50"))
                                .isInventoryItem(true)
                                .build(),
                        GoodsReceivedEvent.ReceivedLineItem.builder()
                                .productId(testProductId2)
                                .description("Shipping Fee")
                                .quantity(new BigDecimal("1.00"))
                                .unitPrice(new BigDecimal("50.00"))
                                .isInventoryItem(false)
                                .build()))
                .build();
    }

    @Test
    @DisplayName(
            "The bill number's sequence is drawn from the tenant's own counter for the month, which moves on by one")
    void billNumberIsDrawnFromTheTenantsCounter() {
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());
        when(billRepository.saveAndFlush(any(VendorBill.class))).thenAnswer(saved -> {
            VendorBill persisted = saved.getArgument(0);
            persisted.setVendorBillId(UUID.fromString("00000000-0000-0000-0000-000000000051"));
            return persisted;
        });

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        ArgumentCaptor<VendorBill> saved = ArgumentCaptor.forClass(VendorBill.class);
        verify(billRepository).saveAndFlush(saved.capture());
        // Vendor prefix, the day the bill is recorded (the fixed clock's), the counter's value.
        assertThat(saved.getValue().getBillNumber()).isEqualTo("BILL_00000000_20240101_0000042");
        assertThat(billCounter.getNextValue()).isEqualTo(43L);
        verify(sequenceLocker).lockOrProvision("BILL-202401");
    }

    @Test
    @DisplayName("#2558: a bill recorded at 2026-01-31T23:30-06:00 takes January's number in a Chicago calendar")
    void billNumberMonthIsTheTenantCalendarMonth() {
        Clock utc = Clock.fixed(TestZoneResolvers.JAN_31_2330_CHICAGO, java.time.ZoneOffset.UTC);
        org.springframework.test.util.ReflectionTestUtils.setField(vendorBillService, "clock", utc);
        org.springframework.test.util.ReflectionTestUtils.setField(
                vendorBillService, "zoneResolver", TestZoneResolvers.fixed(TestZoneResolvers.CHICAGO, utc));
        when(sequenceLocker.lockOrProvision("BILL-202601")).thenReturn(billCounter);
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());
        when(billRepository.saveAndFlush(any(VendorBill.class))).thenAnswer(saved -> {
            VendorBill persisted = saved.getArgument(0);
            persisted.setVendorBillId(UUID.fromString("00000000-0000-0000-0000-000000000052"));
            return persisted;
        });

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        verify(sequenceLocker).lockOrProvision("BILL-202601");
        ArgumentCaptor<VendorBill> saved = ArgumentCaptor.forClass(VendorBill.class);
        verify(billRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getBillNumber()).contains("_20260131_");
    }

    @Test
    @DisplayName("Should record vendor in directory when bill is created")
    void shouldRecordVendorInDirectory() {
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());
        when(billRepository.saveAndFlush(any(VendorBill.class))).thenReturn(createSavedBill());

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        // On the bill's own connection (#2501): the counter row lock is held, so no second
        // connection may be requested.
        verify(vendorDirectoryService).recordVendorInCurrentTransaction(testVendorId, "Test Vendor Inc");
    }

    @Test
    @DisplayName("The directory row is written after the bill is flushed, on the bill's connection")
    void directoryRowIsWrittenAfterTheBill() {
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());
        when(billRepository.saveAndFlush(any(VendorBill.class))).thenReturn(createSavedBill());

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        InOrder order = inOrder(billRepository, vendorDirectoryService);
        order.verify(billRepository).saveAndFlush(any(VendorBill.class));
        order.verify(vendorDirectoryService).recordVendorInCurrentTransaction(testVendorId, "Test Vendor Inc");
    }

    @Test
    @DisplayName("AW37 (ruling Q1 AC1): creation posts nothing and records no accounting event; the bill is"
            + " PENDING_RECEIPT_MATCH with its received lines")
    void creationPostsNothing() {
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.empty());
        when(billRepository.saveAndFlush(any(VendorBill.class))).thenReturn(createSavedBill());

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        ArgumentCaptor<VendorBill> saved = ArgumentCaptor.forClass(VendorBill.class);
        verify(billRepository).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(VendorBillStatus.PENDING_RECEIPT_MATCH);
        assertThat(saved.getValue().getJournalEntryId()).isNull();
        ArgumentCaptor<VendorBillLine> lines = ArgumentCaptor.forClass(VendorBillLine.class);
        verify(billLineRepository, times(2)).save(lines.capture());
        assertThat(lines.getAllValues())
                .extracting(VendorBillLine::getLineTotal)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1250.00"), new BigDecimal("50.00"));
        assertThat(lines.getAllValues())
                .extracting(VendorBillLine::isInventoryItem)
                .containsExactly(true, false);
        // The service holds no event publisher, no posting service and no ingestion path any more: the only
        // collaborators it touches are the bill, its lines, the directory, the guard and the counter.
        verifyNoMoreInteractions(auditLogs, reader);
    }

    @Test
    @DisplayName("A replayed goods-received event returns the existing bill and creates nothing")
    void replayedEventCreatesNothing() {
        VendorBill existingBill = createSavedBill();
        when(billRepository.findByOriginEventId(testEvent.getEventId())).thenReturn(Optional.of(existingBill));

        vendorBillService.handleGoodsReceivedEvent(testEvent);

        verify(billRepository, never()).saveAndFlush(any(VendorBill.class));
        verify(billLineRepository, never()).save(any(VendorBillLine.class));
        verify(reader).read(existingBill);
    }

    private VendorBill createSavedBill() {
        VendorBill bill = new VendorBill();
        bill.setVendorBillId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        bill.setVendorId(testVendorId);
        bill.setVendorName("Test Vendor Inc");
        bill.setBillNumber("BILL-2026-00456");
        bill.setBillDate(LocalDateTime.of(2026, 2, 11, 10, 30));
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setOriginEventId(testEvent.getEventId());
        bill.setOriginEventType("GOODS_RECEIVED");
        bill.setPurchaseOrderId(testPoId);
        bill.setTotalAmount(new BigDecimal("1300.00"));
        bill.setCreatedBy("system");
        bill.setModifiedBy("system");
        return bill;
    }
}
