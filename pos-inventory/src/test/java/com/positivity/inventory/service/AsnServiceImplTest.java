package com.positivity.inventory.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import com.positivity.inventory.internal.dto.asn.AsnResponse;
import com.positivity.inventory.internal.dto.asn.CreateAsnLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateAsnRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptLineRequest;
import com.positivity.inventory.internal.dto.asn.CreateGoodsReceiptRequest;
import com.positivity.inventory.internal.dto.asn.GoodsReceiptResponse;
import com.positivity.inventory.internal.entity.AdvanceShippingNoticeEntity;
import com.positivity.inventory.internal.entity.AsnLineEntity;
import com.positivity.inventory.internal.entity.GoodsReceiptEntity;
import com.positivity.inventory.internal.entity.PurchaseOrderEntity;
import com.positivity.inventory.internal.enums.AsnStatus;
import com.positivity.inventory.internal.enums.PurchaseOrderStatus;
import com.positivity.inventory.internal.exception.DuplicateAsnException;
import com.positivity.inventory.internal.exception.InvalidPoReferenceException;
import com.positivity.inventory.internal.exception.OverReceiptNotPermittedException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.AsnLineRepository;
import com.positivity.inventory.internal.repository.AsnRepository;
import com.positivity.inventory.internal.repository.GoodsReceiptRepository;
import com.positivity.inventory.internal.repository.InventoryLedgerEntryRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderLineRepository;
import com.positivity.inventory.internal.repository.PurchaseOrderRepository;
import com.positivity.inventory.internal.service.AsnServiceImpl;
import com.positivity.security.common.GatewaySecurityConstants;

@ExtendWith(MockitoExtension.class)
@DisplayName("AsnServiceImpl Unit Tests")
class AsnServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(java.time.Instant.parse("2024-01-01T00:00:00Z"),
            java.time.ZoneOffset.UTC);

    @Mock
    private AsnRepository asnRepository;

    @Mock
    private AsnLineRepository asnLineRepository;

    @Mock
    private GoodsReceiptRepository goodsReceiptRepository;

    @Mock
    private PurchaseOrderRepository purchaseOrderRepository;

    @Mock
    private PurchaseOrderLineRepository purchaseOrderLineRepository;

    @Mock
    private InventoryLedgerEntryRepository inventoryLedgerEntryRepository;

    @Mock
    private ApplicationEventPublisher applicationEventPublisher;

    private AsnServiceImpl asnService;

    @BeforeEach
    void setUp() {
        asnService = new AsnServiceImpl(FIXED_CLOCK,
                asnRepository,
                asnLineRepository,
                goodsReceiptRepository,
                purchaseOrderRepository,
                purchaseOrderLineRepository,
                inventoryLedgerEntryRepository,
                applicationEventPublisher);
        authenticateAs("asn-test-user");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username) {
        var authentication = new UsernamePasswordAuthenticationToken(username, "N/A", List.of());
        authentication.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @Test
    @DisplayName("createAsn emits ASNLoaded event and no GL-related events")
    void createAsn_emitsAsnLoadedEvent_butNoGlEvent() {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID vendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");

        CreateAsnLineRequest lineRequest = new CreateAsnLineRequest();
        lineRequest.setPoId(poId);
        lineRequest.setSku("SKU-TEST-001");
        lineRequest.setQuantityShipped(BigDecimal.TEN);
        lineRequest.setUnitCostMinor(5_000L);

        CreateAsnRequest request = new CreateAsnRequest();
        request.setVendorId(vendorId);
        request.setAsnReferenceNumber("ASN-2026-UNIT-001");
        request.setRelatedPoIds(List.of(poId));
        request.setShipDate(LocalDate.now(FIXED_CLOCK));
        request.setExpectedArrivalDate(LocalDate.now(FIXED_CLOCK).plusDays(2));
        request.setLineItems(List.of(lineRequest));

        PurchaseOrderEntity approvedPo = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .vendorId(vendorId)
                .poNumber("PO-UNIT-001")
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L)
                .createdBy("system")
                .build();

        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(approvedPo));
        when(asnRepository.findByVendorIdAndAsnReferenceNumber(vendorId, "ASN-2026-UNIT-001"))
                .thenReturn(Optional.empty());
        when(asnRepository.save(any(AdvanceShippingNoticeEntity.class)))
                .thenAnswer(invocation -> {
                    AdvanceShippingNoticeEntity entity = invocation.getArgument(0);
                    entity.setAsnId(asnId);
                    if (entity.getLines() == null) {
                        entity.setLines(new ArrayList<>());
                    }
                    return entity;
                });
        when(asnLineRepository.saveAll(anyList()))
                .thenAnswer(invocation -> {
                    @SuppressWarnings("unchecked")
                    List<AsnLineEntity> lines = (List<AsnLineEntity>) invocation.getArgument(0);
                    return lines;
                });

        asnService.createAsn(request, "asn-unit-tester");

        verify(applicationEventPublisher, times(1)).publishEvent(argThat((Object event) -> {
            if (!(event instanceof Map<?, ?> eventMap)) {
                return false;
            }
            return "ASNLoaded".equals(eventMap.get("type"));
        }));

        ArgumentCaptor<Object> publishedEventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(applicationEventPublisher, atLeastOnce()).publishEvent(publishedEventCaptor.capture());

        List<String> publishedTypes = publishedEventCaptor.getAllValues().stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(event -> {
                    Object type = event.get("type");
                    if (type == null) {
                        type = event.get("eventType");
                    }
                    return type == null ? "" : type.toString();
                })
                .toList();

        assertTrue(publishedTypes.stream().noneMatch(this::containsForbiddenAccountingKeyword));
    }

    private boolean containsForbiddenAccountingKeyword(String eventType) {
        String lower = eventType == null ? "" : eventType.toLowerCase();
        return lower.contains("gl")
                || lower.contains("journal")
                || lower.contains("accrual")
                || lower.contains("encumbrance");
    }

    @Test
    @DisplayName("createAsn throws InvalidPoReferenceException for non-existent PO")
    void createAsn_throwsInvalidPoReferenceException_forNonExistentPO() {
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        CreateAsnRequest request = new CreateAsnRequest();
        request.setVendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        request.setAsnReferenceNumber("ASN-INVALID-PO");
        request.setRelatedPoIds(List.of(poId));
        request.setLineItems(Collections.emptyList());

        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.empty());

        assertThrows(InvalidPoReferenceException.class, () -> {
            asnService.createAsn(request, "test-user");
        });
    }

    @Test
    @DisplayName("createAsn throws DuplicateAsnException for existing ASN")
    void createAsn_throwsDuplicateAsnException_forExistingAsn() {
        UUID vendorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String asnRef = "ASN-DUPLICATE";
        CreateAsnRequest request = new CreateAsnRequest();
        request.setVendorId(vendorId);
        request.setAsnReferenceNumber(asnRef);
        request.setRelatedPoIds(Collections.emptyList());
        request.setLineItems(Collections.emptyList());

        when(asnRepository.findByVendorIdAndAsnReferenceNumber(vendorId, asnRef))
                .thenReturn(Optional.of(new AdvanceShippingNoticeEntity()));

        assertThrows(DuplicateAsnException.class, () -> {
            asnService.createAsn(request, "test-user");
        });
    }

    @Test
    @DisplayName("getAsn returns AsnResponse when found")
    void getAsn_returnsAsnResponse_whenFound() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        AdvanceShippingNoticeEntity asnEntity = AdvanceShippingNoticeEntity.builder().asnId(asnId).build();
        when(asnRepository.findById(asnId)).thenReturn(Optional.of(asnEntity));

        AsnResponse response = asnService.getAsn(asnId);

        assertNotNull(response);
        assertEquals(asnId, response.getAsnId());
    }

    @Test
    @DisplayName("getAsn throws ResourceNotFoundException when not found")
    void getAsn_throwsResourceNotFoundException_whenNotFound() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(asnRepository.findById(asnId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            asnService.getAsn(asnId);
        });
    }

    @Test
    @DisplayName("createGoodsReceipt saves receipt and emits events")
    void createGoodsReceipt_savesReceiptAndEmitsEvents() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String actor = actorId.toString();

        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku("SKU-TEST-001");
        line.setQuantityReceived(BigDecimal.ONE);
        line.setUnitCostMinor(5_000L);

        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setAsnId(asnId);
        request.setPoId(poId);
        request.setLocationId(locationId);
        request.setLines(List.of(line));

        PurchaseOrderEntity approvedPo = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .poNumber("PO-UNIT-GR-001")
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L)
                .createdBy("system")
                .build();

        AsnLineEntity asnLine = AsnLineEntity.builder()
            .purchaseOrder(approvedPo)
                .sku("SKU-TEST-001")
                .quantityShipped(BigDecimal.TEN)
                .quantityReceived(BigDecimal.ZERO)
                .build();
        AdvanceShippingNoticeEntity asn = AdvanceShippingNoticeEntity.builder()
                .asnId(asnId)
                .status(AsnStatus.LOADED)
                .lines(new ArrayList<>(List.of(asnLine)))
                .build();
        asnLine.setAsn(asn);

        when(asnRepository.findById(asnId)).thenReturn(Optional.of(asn));
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(approvedPo));
        when(goodsReceiptRepository.save(any(GoodsReceiptEntity.class))).thenAnswer(invocation -> {
            GoodsReceiptEntity entity = invocation.getArgument(0);
            if (entity.getReceiptId() == null) {
                entity.setReceiptId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });

        GoodsReceiptResponse response = asnService.createGoodsReceipt(request, actor);

        assertNotNull(response);
        verify(goodsReceiptRepository, times(1)).save(any(GoodsReceiptEntity.class));
        verify(applicationEventPublisher, times(2)).publishEvent(any(Map.class));
    }

    @Test
    @DisplayName("createGoodsReceipt throws OverReceiptNotPermittedException")
    void createGoodsReceipt_throwsOverReceiptNotPermittedException() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID actorId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String actor = actorId.toString();

        CreateGoodsReceiptLineRequest line = new CreateGoodsReceiptLineRequest();
        line.setSku("SKU-OVR-001");
        line.setQuantityReceived(BigDecimal.TEN);
        line.setUnitCostMinor(1L);

        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setAsnId(asnId);
        request.setPoId(poId);
        request.setLocationId(locationId);
        request.setLines(List.of(line));

        PurchaseOrderEntity approvedPo = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .poNumber("PO-UNIT-GR-002")
                .currency("USD")
                .subtotalMinor(10L)
                .taxMinor(0L)
                .grandTotalMinor(10L)
                .openBalanceMinor(1L)
                .createdBy("system")
                .build();

        AsnLineEntity asnLine = AsnLineEntity.builder()
            .purchaseOrder(approvedPo)
                .sku("SKU-OVR-001")
                .quantityShipped(BigDecimal.ONE)
                .quantityReceived(BigDecimal.ZERO)
                .build();

        AdvanceShippingNoticeEntity asn = AdvanceShippingNoticeEntity.builder()
                .asnId(asnId)
                .status(AsnStatus.LOADED)
                .lines(List.of(asnLine))
                .build();
        asnLine.setAsn(asn);

        when(asnRepository.findById(asnId)).thenReturn(Optional.of(asn));
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(approvedPo));

        assertThrows(OverReceiptNotPermittedException.class, () -> {
            asnService.createGoodsReceipt(request, actor);
        });
    }

    @Test
    @DisplayName("createGoodsReceipt sets status to PARTIALLY_RECEIVED")
    void createGoodsReceipt_setsStatusToPartiallyReceived() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String actor = "test-user";

        CreateGoodsReceiptLineRequest line1 = new CreateGoodsReceiptLineRequest();
        line1.setSku("SKU-1");
        line1.setQuantityReceived(BigDecimal.ONE);
        line1.setUnitCostMinor(100L);

        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setAsnId(asnId);
        request.setPoId(poId);
        request.setLocationId(locationId);
        request.setLines(List.of(line1));

        PurchaseOrderEntity approvedPo = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .poNumber("PO-UNIT-GR-003")
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L)
                .createdBy("system")
                .build();

        AsnLineEntity asnLine1 = AsnLineEntity.builder()
            .purchaseOrder(approvedPo)
                .sku("SKU-1")
                .quantityShipped(BigDecimal.TEN)
                .quantityReceived(BigDecimal.ZERO)
                .build();
        AsnLineEntity asnLine2 = AsnLineEntity.builder()
            .purchaseOrder(approvedPo)
                .sku("SKU-2")
                .quantityShipped(BigDecimal.TEN)
                .quantityReceived(BigDecimal.ZERO)
                .build();

        AdvanceShippingNoticeEntity asn = AdvanceShippingNoticeEntity.builder()
                .asnId(asnId)
                .status(AsnStatus.LOADED)
                .lines(List.of(asnLine1, asnLine2))
                .build();
        asnLine1.setAsn(asn);
        asnLine2.setAsn(asn);

        when(asnRepository.findById(asnId)).thenReturn(Optional.of(asn));
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(approvedPo));
        when(goodsReceiptRepository.save(any(GoodsReceiptEntity.class))).thenAnswer(invocation -> {
            GoodsReceiptEntity entity = invocation.getArgument(0);
            if (entity.getReceiptId() == null) {
                entity.setReceiptId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });

        asnService.createGoodsReceipt(request, actor);

        ArgumentCaptor<AdvanceShippingNoticeEntity> captor = ArgumentCaptor.forClass(AdvanceShippingNoticeEntity.class);
        verify(asnRepository).save(captor.capture());
        assertEquals(AsnStatus.PARTIALLY_RECEIVED, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("createGoodsReceipt sets status to FULLY_RECEIVED")
    void createGoodsReceipt_setsStatusToFullyReceived() {
        UUID asnId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID poId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID locationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        String actor = "test-user";

        CreateGoodsReceiptLineRequest line1 = new CreateGoodsReceiptLineRequest();
        line1.setSku("SKU-1");
        line1.setQuantityReceived(BigDecimal.TEN);
        line1.setUnitCostMinor(100L);

        CreateGoodsReceiptRequest request = new CreateGoodsReceiptRequest();
        request.setAsnId(asnId);
        request.setPoId(poId);
        request.setLocationId(locationId);
        request.setLines(List.of(line1));

        PurchaseOrderEntity approvedPo = PurchaseOrderEntity.builder()
                .purchaseOrderId(poId)
                .status(PurchaseOrderStatus.APPROVED)
                .vendorId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .poNumber("PO-UNIT-GR-004")
                .currency("USD")
                .subtotalMinor(10_000L)
                .taxMinor(0L)
                .grandTotalMinor(10_000L)
                .openBalanceMinor(10_000L)
                .createdBy("system")
                .build();

        AsnLineEntity asnLine1 = AsnLineEntity.builder()
            .purchaseOrder(approvedPo)
                .sku("SKU-1")
                .quantityShipped(BigDecimal.TEN)
                .quantityReceived(BigDecimal.ZERO)
                .build();
        AdvanceShippingNoticeEntity asn = AdvanceShippingNoticeEntity.builder()
                .asnId(asnId)
                .status(AsnStatus.LOADED)
                .lines(List.of(asnLine1))
                .build();
        asnLine1.setAsn(asn);

        when(asnRepository.findById(asnId)).thenReturn(Optional.of(asn));
        when(purchaseOrderRepository.findById(poId)).thenReturn(Optional.of(approvedPo));
        when(goodsReceiptRepository.save(any(GoodsReceiptEntity.class))).thenAnswer(invocation -> {
            GoodsReceiptEntity entity = invocation.getArgument(0);
            if (entity.getReceiptId() == null) {
                entity.setReceiptId(UUID.fromString("00000000-0000-0000-0000-000000000001"));
            }
            return entity;
        });

        asnService.createGoodsReceipt(request, actor);

        ArgumentCaptor<AdvanceShippingNoticeEntity> captor = ArgumentCaptor.forClass(AdvanceShippingNoticeEntity.class);
        verify(asnRepository).save(captor.capture());
        assertEquals(AsnStatus.FULLY_RECEIVED, captor.getValue().getStatus());
    }

    @Test
    @DisplayName("getGoodsReceipt returns GoodsReceiptResponse when found")
    void getGoodsReceipt_returnsGoodsReceiptResponse_whenFound() {
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        GoodsReceiptEntity receiptEntity = GoodsReceiptEntity.builder().receiptId(receiptId).build();
        when(goodsReceiptRepository.findById(receiptId)).thenReturn(Optional.of(receiptEntity));

        GoodsReceiptResponse response = assertDoesNotThrow(() -> asnService.getGoodsReceipt(receiptId));

        assertNotNull(response);
        assertEquals(receiptId, response.getReceiptId());
    }

    @Test
    @DisplayName("getGoodsReceipt throws ResourceNotFoundException when not found")
    void getGoodsReceipt_throwsResourceNotFoundException_whenNotFound() {
        UUID receiptId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        when(goodsReceiptRepository.findById(receiptId)).thenReturn(Optional.empty());

        assertThrows(ResourceNotFoundException.class, () -> {
            asnService.getGoodsReceipt(receiptId);
        });
    }
}
