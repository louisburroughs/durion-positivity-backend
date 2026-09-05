package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.order.internal.client.CustomerLookupResult;
import com.positivity.order.internal.client.CustomerPort;
import com.positivity.order.internal.client.InventoryPort;
import com.positivity.order.internal.client.InventoryResult;
import com.positivity.order.internal.client.InvoiceRef;
import com.positivity.order.internal.client.InvoicingPort;
import com.positivity.order.internal.client.PricingPort;
import com.positivity.order.internal.client.PricingQuote;
import com.positivity.order.internal.client.SourceDocumentLine;
import com.positivity.order.internal.client.SourceDocumentPort;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CustomerValidationStatus;
import com.positivity.order.internal.entity.ExtBillingRules;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.ExtProduct;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.OrderDiscountType;
import com.positivity.order.internal.entity.OrderPaymentRecord;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.RegisterSession;
import com.positivity.order.internal.entity.RegisterSessionStatus;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.CartIdempotencyConflictException;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.InvalidSkuException;
import com.positivity.order.internal.exception.OrderVoidBlockedException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderStateConflictException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.AddItemCommand;
import com.positivity.order.internal.service.model.CheckoutResult;
import com.positivity.order.internal.service.model.CreateCartCommand;
import com.positivity.order.internal.service.model.CreateCartResult;
import com.positivity.order.internal.service.model.OrderDiscountCommand;
import com.positivity.order.internal.service.model.SalesOrderLineSummary;
import com.positivity.order.internal.service.model.SalesOrderSummary;
import com.positivity.security.common.GatewaySecurityConstants;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Counter cart lifecycle: cart creation and its idempotent replay (story A2/I1), line adds with
 * pricing, manual-price permission, and serial capture (B1/B2/H3), source-document import and
 * merge (E1, spec R7.1), order-level discounts (B2, spec R3.10), quoting (A3, resolved Q3),
 * checkout with ON_ACCOUNT eligibility (C1/C2/C4, spec R4.5/R11.2), and voiding (spec R2.4).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalesOrderServiceImpl cart lifecycle")
class SalesOrderCartLifecycleTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7001");
    private static final UUID OTHER_ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7002");
    private static final UUID LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7003");
    private static final UUID NEW_LINE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7004");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7005");
    private static final UUID SESSION_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7006");
    private static final UUID SESSION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7007");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7008");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7009");
    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f700a");
    private static final UUID CLIENT_LINE_UUID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f700b");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f700c");

    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final String TERMINAL = "TERM-1";
    private static final String CLERK = "clerk-7";
    private static final String MANUAL_PRICE_AUTHORITY = "order:line:enter_manual_price";
    private static final String ON_ACCOUNT_AUTHORITY = "order:order:charge_on_account";

    @Mock
    private SalesOrderRepository salesOrderRepository;

    @Mock
    private SalesOrderLineRepository salesOrderLineRepository;

    @Mock
    private PricingPort pricingPort;

    @Mock
    private InventoryPort inventoryPort;

    @Mock
    private SourceDocumentPort sourceDocumentPort;

    @Mock
    private CustomerPort customerPort;

    @Mock
    private InvoicingPort invoicingPort;

    @Mock
    private ExtProductRepository extProductRepository;

    @Mock
    private ExtCustomerRepository extCustomerRepository;

    @Mock
    private ExtBillingRulesRepository extBillingRulesRepository;

    @Mock
    private OrderPaymentRecordRepository paymentRecordRepository;

    @Mock
    private RegisterSessionRepository registerSessionRepository;

    @Mock
    private OrderDomainEventPublisher domainEventPublisher;

    @Mock
    private OrderStateMachine orderStateMachine;

    @Mock
    private OrderNumberService orderNumberService;

    @Mock
    private OrderTotalsCalculator totalsCalculator;

    @Mock
    private OrderTaxService orderTaxService;

    private SalesOrderServiceImpl service;

    /**
     * CAP #1315: checkout registers demand through this publisher, which is absent whenever Kafka
     * is disabled. These unit tests exercise the labelling half of the gate, so they supply the
     * absent case — the publishing half is covered in SalesOrderCheckoutReservationTest.
     */
    @SuppressWarnings("unchecked")
    private final org.springframework.beans.factory.ObjectProvider<
                    com.positivity.order.internal.config.InventoryCommandPublisher>
            inventoryCommandPublisherProvider =
                    org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

    @BeforeEach
    void setUp() {
        service = new SalesOrderServiceImpl(
                salesOrderRepository,
                salesOrderLineRepository,
                pricingPort,
                inventoryPort,
                sourceDocumentPort,
                customerPort,
                invoicingPort,
                extProductRepository,
                extCustomerRepository,
                extBillingRulesRepository,
                paymentRecordRepository,
                registerSessionRepository,
                domainEventPublisher,
                orderStateMachine,
                orderNumberService,
                totalsCalculator,
                orderTaxService,
                inventoryCommandPublisherProvider,
                Clock.fixed(NOW, ZoneOffset.UTC));
        // @Value-injected field; no Spring context here.
        ReflectionTestUtils.setField(service, "quoteValidity", Duration.ofDays(7));

        authenticate(MANUAL_PRICE_AUTHORITY, ON_ACCOUNT_AUTHORITY);

        when(orderNumberService.nextNumber(any())).thenReturn("SO-0001");
        when(registerSessionRepository.findFirstByTerminalIdAndStatus(anyString(), any()))
                .thenReturn(Optional.empty());
        when(salesOrderRepository.save(any())).thenAnswer(invocation -> {
            SalesOrder order = invocation.getArgument(0);
            if (order.getOrderId() == null) {
                order.setOrderId(ORDER_ID);
            }
            return order;
        });
        when(salesOrderLineRepository.save(any())).thenAnswer(invocation -> {
            SalesOrderLine line = invocation.getArgument(0);
            if (line.getOrderLineId() == null) {
                line.setOrderLineId(NEW_LINE_ID);
            }
            return line;
        });
        when(inventoryPort.checkAvailability(anyString(), any(), any()))
                .thenReturn(new InventoryResult(true, BigDecimal.valueOf(99)));
        when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(priced("12.50", "Oil filter"));
        when(paymentRecordRepository.findByOrderId(any())).thenReturn(List.of());
        when(salesOrderRepository.findByCheckoutIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(salesOrderRepository.findByCreationIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static void authenticate(String... authorities) {
        UsernamePasswordAuthenticationToken token = new UsernamePasswordAuthenticationToken(
                "jane.smith",
                "n/a",
                List.of(authorities).stream()
                        .map(SimpleGrantedAuthority::new)
                        .map(a -> (org.springframework.security.core.GrantedAuthority) a)
                        .toList());
        token.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, "jane.smith"));
        SecurityContextHolder.getContext().setAuthentication(token);
    }

    private static PricingQuote priced(String unitPrice, String description) {
        return new PricingQuote(
                PricingQuote.Status.PRICED, new BigDecimal(unitPrice), null, description, null, "{\"rules\":[]}");
    }

    private static CreateCartCommand createCommand() {
        return new CreateCartCommand(CLERK, TERMINAL, null, null, LOCATION_ID, null, null, null, null, null);
    }

    private SalesOrder order(SalesOrderStatus status) {
        SalesOrder order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .orderNumber("SO-0001")
                .locationId(LOCATION_ID)
                .clerkId(CLERK)
                .terminalId(TERMINAL)
                .status(status)
                .subtotal(new BigDecimal("25.0000"))
                .taxTotal(new BigDecimal("2.0000"))
                .grandTotal(new BigDecimal("27.0000"))
                .lines(new ArrayList<>())
                .build();
        order.setCreatedAt(NOW);
        order.setUpdatedAt(NOW);
        return order;
    }

    private static SalesOrderLine line(SalesOrder order, String sku, int quantity) {
        SalesOrderLine line = SalesOrderLine.builder()
                .orderLineId(LINE_ID)
                .order(order)
                .itemSku(sku)
                .itemDescription("Oil filter")
                .quantity(quantity)
                .unitPrice(new BigDecimal("12.5000"))
                .priceSource(PriceSource.PRICING_SERVICE)
                .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                .lineSubtotal(new BigDecimal("25.0000"))
                .taxAmount(new BigDecimal("2.0000"))
                .lineTotal(new BigDecimal("27.0000"))
                .serialNumbers(new ArrayList<>())
                .build();
        order.getLines().add(line);
        return line;
    }

    private void givenOrder(SalesOrder order) {
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
    }

    private SalesOrder savedOrder() {
        ArgumentCaptor<SalesOrder> captor = ArgumentCaptor.forClass(SalesOrder.class);
        verify(salesOrderRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("createCart")
    class CreateCart {

        @Test
        @DisplayName("creates a DRAFT cart stamped with the actor and a generated order number")
        void createsDraftCart() {
            CreateCartResult result = service.createCart(createCommand());

            assertThat(result.replay()).isFalse();
            SalesOrder saved = savedOrder();
            assertThat(saved.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
            assertThat(saved.getOrderNumber()).isEqualTo("SO-0001");
            assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(saved.getCreatedBy()).isEqualTo("jane.smith");
            assertThat(saved.getUpdatedBy()).isEqualTo("jane.smith");
            assertThat(saved.getSubtotal()).isEqualByComparingTo("0");
            verify(orderStateMachine).recordCreation(saved);
        }

        @Test
        @DisplayName("treats a blank idempotency key as absent so it is never persisted or looked up")
        void blankIdempotencyKeyIsIgnored() {
            service.createCart(
                    new CreateCartCommand(CLERK, TERMINAL, null, null, LOCATION_ID, null, null, "  ", null, null));

            verify(salesOrderRepository, never()).findByCreationIdempotencyKey(anyString());
            assertThat(savedOrder().getCreationIdempotencyKey()).isNull();
        }

        @Test
        @DisplayName("replays the original cart for a repeated idempotency key")
        void replaysOnIdempotencyKey() {
            SalesOrder existing = order(SalesOrderStatus.DRAFT);
            existing.setClerkId(CLERK);
            existing.setTerminalId(TERMINAL);
            when(salesOrderRepository.findByCreationIdempotencyKey("cart-1")).thenReturn(Optional.of(existing));

            CreateCartResult result = service.createCart(
                    new CreateCartCommand(CLERK, TERMINAL, null, null, LOCATION_ID, null, null, "cart-1", null, null));

            assertThat(result.replay()).isTrue();
            assertThat(result.summary().orderNumber()).isEqualTo("SO-0001");
            verify(salesOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("rejects a replay whose payload differs from the original")
        void rejectsDivergentReplay() {
            SalesOrder existing = order(SalesOrderStatus.DRAFT);
            existing.setLabel("morning bay");
            when(salesOrderRepository.findByCreationIdempotencyKey("cart-1")).thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, TERMINAL, null, null, LOCATION_ID, "different label", null, "cart-1", null, null)))
                    .isInstanceOf(CartIdempotencyConflictException.class)
                    .hasMessageContaining("different cart-creation payload");
        }

        @Test
        @DisplayName("treats a blank label on replay as equal to the original null")
        void blankLabelMatchesNullOnReplay() {
            when(salesOrderRepository.findByCreationIdempotencyKey("cart-1"))
                    .thenReturn(Optional.of(order(SalesOrderStatus.DRAFT)));

            assertThat(service.createCart(new CreateCartCommand(
                                    CLERK, TERMINAL, null, null, LOCATION_ID, "   ", null, "cart-1", null, null))
                            .replay())
                    .isTrue();
        }

        @Test
        @DisplayName("rejects a replay that changes the clerk or the terminal")
        void rejectsReplayWithDifferentClerkOrTerminal() {
            when(salesOrderRepository.findByCreationIdempotencyKey("cart-1"))
                    .thenReturn(Optional.of(order(SalesOrderStatus.DRAFT)));

            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            "other-clerk", TERMINAL, null, null, LOCATION_ID, null, null, "cart-1", null, null)))
                    .isInstanceOf(CartIdempotencyConflictException.class);

            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, "TERM-9", null, null, LOCATION_ID, null, null, "cart-1", null, null)))
                    .isInstanceOf(CartIdempotencyConflictException.class);
        }

        @Test
        @DisplayName("binds an open register session and takes its location when none was supplied")
        void bindsOpenSession() {
            when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.OPEN))
                    .thenReturn(Optional.of(RegisterSession.builder()
                            .sessionId(SESSION_ID)
                            .terminalId(TERMINAL)
                            .locationId(SESSION_LOCATION_ID)
                            .status(RegisterSessionStatus.OPEN)
                            .build()));

            service.createCart(new CreateCartCommand(CLERK, TERMINAL, null, null, null, null, null, null, null, null));

            SalesOrder saved = savedOrder();
            assertThat(saved.getSessionId()).isEqualTo(SESSION_ID);
            assertThat(saved.getLocationId()).isEqualTo(SESSION_LOCATION_ID);
        }

        @Test
        @DisplayName("prefers an explicit location over the session's")
        void explicitLocationWins() {
            when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.OPEN))
                    .thenReturn(Optional.of(RegisterSession.builder()
                            .sessionId(SESSION_ID)
                            .locationId(SESSION_LOCATION_ID)
                            .status(RegisterSessionStatus.OPEN)
                            .build()));

            service.createCart(createCommand());

            assertThat(savedOrder().getLocationId()).isEqualTo(LOCATION_ID);
        }

        @Test
        @DisplayName("refuses a new cart on a terminal whose session is being closed")
        void refusesClosingTerminal() {
            when(registerSessionRepository.findFirstByTerminalIdAndStatus(TERMINAL, RegisterSessionStatus.CLOSING))
                    .thenReturn(Optional.of(RegisterSession.builder()
                            .sessionId(SESSION_ID)
                            .status(RegisterSessionStatus.CLOSING)
                            .build()));

            assertThatThrownBy(() -> service.createCart(createCommand()))
                    .isInstanceOf(SalesOrderStateConflictException.class)
                    .hasMessageContaining("register session being closed");
        }

        @Test
        @DisplayName("requires a location when no session is open on the terminal")
        void requiresLocationWithoutSession() {
            assertThatThrownBy(() -> service.createCart(
                            new CreateCartCommand(CLERK, TERMINAL, null, null, null, null, null, null, null, null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("locationId is required");
        }

        @Test
        @DisplayName("records a validated customer and vehicle")
        void recordsValidatedCustomerAndVehicle() {
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.FOUND);
            when(customerPort.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).thenReturn(CustomerLookupResult.FOUND);

            service.createCart(new CreateCartCommand(
                    CLERK,
                    TERMINAL,
                    CUSTOMER_ID.toString(),
                    VEHICLE_ID.toString(),
                    LOCATION_ID,
                    null,
                    null,
                    null,
                    null,
                    null));

            SalesOrder saved = savedOrder();
            assertThat(saved.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(saved.getVehicleId()).isEqualTo(VEHICLE_ID);
            assertThat(saved.getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.VALIDATED);
        }

        @Test
        @DisplayName("marks the cart PENDING when CRM is unavailable rather than blocking cart work")
        void pendingWhenCrmUnavailable() {
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.UNAVAILABLE);

            service.createCart(new CreateCartCommand(
                    CLERK, TERMINAL, CUSTOMER_ID.toString(), null, LOCATION_ID, null, null, null, null, null));

            assertThat(savedOrder().getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.PENDING);
        }

        @Test
        @DisplayName("marks the cart PENDING when the vehicle lookup is unavailable")
        void pendingWhenVehicleLookupUnavailable() {
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.FOUND);
            when(customerPort.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).thenReturn(CustomerLookupResult.UNAVAILABLE);

            service.createCart(new CreateCartCommand(
                    CLERK,
                    TERMINAL,
                    CUSTOMER_ID.toString(),
                    VEHICLE_ID.toString(),
                    LOCATION_ID,
                    null,
                    null,
                    null,
                    null,
                    null));

            assertThat(savedOrder().getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.PENDING);
        }

        @Test
        @DisplayName("leaves validation unset when no customer is supplied, even with a vehicle")
        void noCustomerLeavesValidationUnset() {
            service.createCart(new CreateCartCommand(
                    CLERK, TERMINAL, null, VEHICLE_ID.toString(), LOCATION_ID, null, null, null, null, null));

            assertThat(savedOrder().getCustomerValidationStatus()).isNull();
            verify(customerPort, never()).lookupCustomer(any());
        }

        @Test
        @DisplayName("rejects a customer or vehicle the CRM does not know")
        void rejectsUnknownCustomerOrVehicle() {
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.NOT_FOUND);
            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, TERMINAL, CUSTOMER_ID.toString(), null, LOCATION_ID, null, null, null, null, null)))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("Customer not found in CRM");

            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.FOUND);
            when(customerPort.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).thenReturn(CustomerLookupResult.NOT_FOUND);
            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK,
                            TERMINAL,
                            CUSTOMER_ID.toString(),
                            VEHICLE_ID.toString(),
                            LOCATION_ID,
                            null,
                            null,
                            null,
                            null,
                            null)))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("not found for customer");
        }

        @Test
        @DisplayName("rejects a reference that is not a UUID")
        void rejectsNonUuidReference() {
            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, TERMINAL, "not-a-uuid", null, LOCATION_ID, null, null, null, null, null)))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("customerId must be a UUID");
        }

        @Test
        @DisplayName("treats a blank reference as absent")
        void blankReferenceIsAbsent() {
            service.createCart(
                    new CreateCartCommand(CLERK, TERMINAL, "  ", null, LOCATION_ID, null, null, null, null, null));

            assertThat(savedOrder().getCustomerId()).isNull();
        }

        @Test
        @DisplayName("records a deposit source pair")
        void recordsDepositSource() {
            service.createCart(new CreateCartCommand(
                    CLERK, TERMINAL, null, null, LOCATION_ID, null, null, null, "ESTIMATE", WORKORDER_ID));

            SalesOrder saved = savedOrder();
            assertThat(saved.getDepositSourceType()).isEqualTo("ESTIMATE");
            assertThat(saved.getDepositSourceId()).isEqualTo(WORKORDER_ID);
        }

        @Test
        @DisplayName("refuses a half-specified deposit source pair in either direction")
        void refusesHalfSpecifiedDepositSource() {
            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, TERMINAL, null, null, LOCATION_ID, null, null, null, "ESTIMATE", null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("must be provided together");

            assertThatThrownBy(() -> service.createCart(new CreateCartCommand(
                            CLERK, TERMINAL, null, null, LOCATION_ID, null, null, null, null, WORKORDER_ID)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("must be provided together");
        }
    }

    @Nested
    @DisplayName("addItem")
    class AddItem {

        @Test
        @DisplayName("prices the line from the pricing port and marks it available")
        void addsPricedLine() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            givenOrder(order);

            SalesOrderLineSummary summary =
                    service.addItem(ORDER_ID, new AddItemCommand("SKU-1", 2, null, null, null, null, null, null));

            assertThat(summary.itemSku()).isEqualTo("SKU-1");
            assertThat(summary.itemDescription()).isEqualTo("Oil filter");
            assertThat(summary.unitPrice()).isEqualByComparingTo("12.5000");
            assertThat(summary.priceSource()).isEqualTo(PriceSource.PRICING_SERVICE.name());
            assertThat(summary.fulfillmentStatus()).isEqualTo(FulfillmentStatus.AVAILABLE.name());
            assertThat(order.getLines()).hasSize(1);
            verify(totalsCalculator).recalculate(order);
        }

        @Test
        @DisplayName("falls back to the SKU as description when the quote carries none")
        void fallsBackToSkuDescription() {
            givenOrder(order(SalesOrderStatus.DRAFT));
            when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(priced("12.50", null));

            assertThat(service.addItem(ORDER_ID, new AddItemCommand("SKU-1", 1, null, null, null, null, null, null))
                            .itemDescription())
                    .isEqualTo("SKU-1");
        }

        @Test
        @DisplayName("marks the line BACKORDER when inventory is short")
        void marksBackorder() {
            givenOrder(order(SalesOrderStatus.DRAFT));
            when(inventoryPort.checkAvailability(anyString(), any(), any()))
                    .thenReturn(new InventoryResult(false, BigDecimal.valueOf(0)));

            assertThat(service.addItem(ORDER_ID, new AddItemCommand("SKU-1", 5, null, null, null, null, null, null))
                            .fulfillmentStatus())
                    .isEqualTo(FulfillmentStatus.BACKORDER.name());
        }

        @Test
        @DisplayName("accepts a manual price when the clerk holds the permission")
        void acceptsPermittedManualPrice() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            SalesOrderLineSummary summary = service.addItem(
                    ORDER_ID,
                    new AddItemCommand("SKU-1", 1, "PRICE_MATCH", new BigDecimal("9.99"), null, null, null, null));

            assertThat(summary.unitPrice()).isEqualByComparingTo("9.9900");
            assertThat(summary.priceSource()).isEqualTo(PriceSource.MANUAL.name());
            assertThat(summary.reasonCode()).isEqualTo("PRICE_MATCH");
            verify(pricingPort, never()).quoteForSku(anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("refuses a manual price without the permission")
        void refusesUnpermittedManualPrice() {
            authenticate();
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID,
                            new AddItemCommand("SKU-1", 1, null, new BigDecimal("9.99"), null, null, null, null)))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("order:line:enter_manual_price");
        }

        @Test
        @DisplayName("rejects an unknown SKU and an unavailable pricing service distinctly")
        void rejectsUnpriceableSkus() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(PricingQuote.unknownSku());
            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID, new AddItemCommand("SKU-1", 1, null, null, null, null, null, null)))
                    .isInstanceOf(InvalidSkuException.class)
                    .hasMessageContaining("Product not found for SKU");

            when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(PricingQuote.unavailable());
            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID, new AddItemCommand("SKU-1", 1, null, null, null, null, null, null)))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("Pricing is unavailable");
        }

        @Test
        @DisplayName("normalizes captured serials, dropping nulls and blanks")
        void normalizesSerials() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            SalesOrderLineSummary summary = service.addItem(
                    ORDER_ID,
                    new AddItemCommand(
                            "SKU-1", 3, null, null, null, null, null, java.util.Arrays.asList(" S1 ", null, "", "S2")));

            assertThat(summary.serialNumbers()).containsExactly("S1", "S2");
        }

        @Test
        @DisplayName("treats absent serials as an empty list")
        void absentSerialsBecomeEmpty() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThat(service.addItem(ORDER_ID, new AddItemCommand("SKU-1", 1, null, null, null, null, null, null))
                            .serialNumbers())
                    .isEmpty();
        }

        @Test
        @DisplayName("refuses more serials than the line quantity")
        void refusesExcessSerials() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID,
                            new AddItemCommand("SKU-1", 1, null, null, null, null, null, List.of("S1", "S2"))))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("exceeds line quantity");
        }

        @Test
        @DisplayName("replays a known client line uuid as a no-op when nothing changed")
        void replaysKnownClientLineUuid() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-1", 2);
            existing.setClientLineUuid(CLIENT_LINE_UUID);
            givenOrder(order);
            when(salesOrderLineRepository.findByOrder_OrderIdAndClientLineUuid(ORDER_ID, CLIENT_LINE_UUID))
                    .thenReturn(Optional.of(existing));

            SalesOrderLineSummary summary = service.addItem(
                    ORDER_ID, new AddItemCommand("SKU-1", 2, null, null, CLIENT_LINE_UUID, null, null, null));

            assertThat(summary.quantity()).isEqualTo(2);
            verify(salesOrderLineRepository, never()).save(any());
        }

        @Test
        @DisplayName("turns a replayed add with a new quantity into an update")
        void replayWithNewQuantityUpdates() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-1", 2);
            existing.setClientLineUuid(CLIENT_LINE_UUID);
            givenOrder(order);
            when(salesOrderLineRepository.findByOrder_OrderIdAndClientLineUuid(ORDER_ID, CLIENT_LINE_UUID))
                    .thenReturn(Optional.of(existing));

            assertThat(service.addItem(
                                    ORDER_ID,
                                    new AddItemCommand("SKU-1", 5, null, null, CLIENT_LINE_UUID, null, null, null))
                            .quantity())
                    .isEqualTo(5);
            assertThat(existing.getQuantity()).isEqualTo(5);
            verify(salesOrderLineRepository).save(existing);
        }

        @Test
        @DisplayName("rejects a replayed uuid reused for a different SKU")
        void rejectsReplayWithDifferentSku() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-1", 2);
            existing.setClientLineUuid(CLIENT_LINE_UUID);
            givenOrder(order);
            when(salesOrderLineRepository.findByOrder_OrderIdAndClientLineUuid(ORDER_ID, CLIENT_LINE_UUID))
                    .thenReturn(Optional.of(existing));

            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID,
                            new AddItemCommand("SKU-OTHER", 2, null, null, CLIENT_LINE_UUID, null, null, null)))
                    .isInstanceOf(CartIdempotencyConflictException.class)
                    .hasMessageContaining("was previously used for SKU");
        }

        @Test
        @DisplayName("rejects an unknown order")
        void rejectsUnknownOrder() {
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.addItem(
                            ORDER_ID, new AddItemCommand("SKU-1", 1, null, null, null, null, null, null)))
                    .isInstanceOf(SalesOrderNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("line mutation and listing")
    class LineMutationAndListing {

        @Test
        @DisplayName("replaces the line on a quantity update and recomputes")
        void updatesQuantity() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-1", 2);
            givenOrder(order);
            when(salesOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.of(existing));

            assertThat(service.updateItemQuantity(ORDER_ID, LINE_ID, 7).quantity())
                    .isEqualTo(7);
            assertThat(order.getLines()).hasSize(1);
            verify(totalsCalculator).recalculate(order);
        }

        @Test
        @DisplayName("rejects a quantity update for an unknown line")
        void rejectsUnknownLine() {
            givenOrder(order(SalesOrderStatus.DRAFT));
            when(salesOrderLineRepository.findById(LINE_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.updateItemQuantity(ORDER_ID, LINE_ID, 3))
                    .isInstanceOf(SalesOrderNotFoundException.class)
                    .hasMessageContaining("Order line not found");
        }

        @Test
        @DisplayName("drops the line on removal and recomputes")
        void removesItem() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-1", 2);
            givenOrder(order);

            service.removeItem(ORDER_ID, LINE_ID);

            assertThat(order.getLines()).isEmpty();
            verify(totalsCalculator).recalculate(order);
        }

        @Test
        @DisplayName("returns the order summary with its lines")
        void getsOrder() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-1", 2);
            givenOrder(order);

            SalesOrderSummary summary = service.getOrder(ORDER_ID);

            assertThat(summary.orderNumber()).isEqualTo("SO-0001");
            assertThat(summary.status()).isEqualTo(SalesOrderStatus.DRAFT.name());
            assertThat(summary.lines()).hasSize(1);
        }

        @Test
        @DisplayName("rejects an unknown order on read and removal")
        void rejectsUnknownOrder() {
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getOrder(ORDER_ID)).isInstanceOf(SalesOrderNotFoundException.class);
            assertThatThrownBy(() -> service.removeItem(ORDER_ID, LINE_ID))
                    .isInstanceOf(SalesOrderNotFoundException.class);
        }

        @Test
        @DisplayName("passes normalized filters and a clamped page request to the repository")
        void listsCartsWithNormalizedFilters() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            when(salesOrderRepository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(order)));

            List<SalesOrderSummary> carts = service.listCarts("  ", "", "QUOTED", -3, 5000);

            assertThat(carts).hasSize(1);
            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(salesOrderRepository).search(eq(null), eq(null), eq(SalesOrderStatus.QUOTED), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isZero();
            assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
        }

        @Test
        @DisplayName("omits the status filter when it is blank and floors the page size at one")
        void listsCartsWithoutStatus() {
            Page<SalesOrder> page = new PageImpl<>(List.of(), PageRequest.of(0, 1), 0);
            when(salesOrderRepository.search(any(), any(), any(), any())).thenReturn(page);

            assertThat(service.listCarts(CLERK, TERMINAL, "  ", 2, 0)).isEmpty();

            ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
            verify(salesOrderRepository).search(eq(CLERK), eq(TERMINAL), eq(null), pageable.capture());
            assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
            assertThat(pageable.getValue().getPageSize()).isEqualTo(1);
        }

        @Test
        @DisplayName("list summaries omit the line detail")
        void listSummariesOmitLines() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-1", 2);
            when(salesOrderRepository.search(any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(order)));

            assertThat(service.listCarts(null, null, null, 0, 20).get(0).lines())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("linkSource")
    class LinkSource {

        private SourceDocumentLine sourceLine(String sku, int quantity, String unitPrice, String sourceLineId) {
            return new SourceDocumentLine(
                    sku, "Imported " + sku, quantity, new BigDecimal(unitPrice), sourceLineId, true);
        }

        @Test
        @DisplayName("imports source lines at their contractual price")
        void importsSourceLines() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(1);
            SalesOrderLine imported = order.getLines().get(0);
            assertThat(imported.getItemSku()).isEqualTo("SKU-A");
            assertThat(imported.getUnitPrice()).isEqualByComparingTo("30.0000");
            assertThat(imported.getPriceSource()).isEqualTo(PriceSource.SOURCE_DOCUMENT);
            assertThat(imported.getSourceType()).isEqualTo(SourceType.ESTIMATE);
            assertThat(imported.getSourceId()).isEqualTo("EST-1");
            assertThat(imported.getSourceLineId()).isEqualTo("L1");
            assertThat(imported.getReturnable()).isTrue();
        }

        @Test
        @DisplayName("merges into an existing unsourced line with the same SKU and price")
        void mergesIntoExistingLine() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-A", 1);
            existing.setUnitPrice(new BigDecimal("30.0000"));
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(1);
            assertThat(existing.getQuantity()).isEqualTo(3);
            assertThat(existing.getSourceType()).isEqualTo(SourceType.ESTIMATE);
            assertThat(existing.getSourceId()).isEqualTo("EST-1");
        }

        @Test
        @DisplayName(
                "merges a re-imported line already linked to the same source without overwriting its source metadata")
        void mergesWithoutOverwritingMetadataWhenAlreadyLinkedToSameSource() {
            // Re-linking the same estimate (e.g. after it was amended) must still merge quantity
            // by SKU+price, but since the line is already attributed to this source, its original
            // sourceLineId must be preserved rather than stomped by whichever source line happened
            // to match on this pass.
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-A", 1);
            existing.setUnitPrice(new BigDecimal("30.0000"));
            existing.setSourceType(SourceType.ESTIMATE);
            existing.setSourceId("EST-1");
            existing.setSourceLineId("L-ORIGINAL");
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(1);
            assertThat(existing.getQuantity()).isEqualTo(3);
            assertThat(existing.getSourceLineId()).isEqualTo("L-ORIGINAL");
        }

        @Test
        @DisplayName("adds a separate line when an existing SKU+price match is linked to a different source")
        void doesNotMergeWhenExistingLineLinkedToDifferentSource() {
            // A line already attributed to another source document must not silently absorb
            // quantity from an unrelated source, even when SKU and price happen to match.
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-A", 1);
            existing.setUnitPrice(new BigDecimal("30.0000"));
            existing.setSourceType(SourceType.WORKORDER);
            existing.setSourceId("WO-OTHER");
            existing.setSourceLineId("WO-L1");
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(2);
            assertThat(existing.getQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("adds a separate line when the price differs")
        void doesNotMergeAtDifferentPrice() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-A", 1);
            existing.setUnitPrice(new BigDecimal("25.0000"));
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(2);
            assertThat(existing.getQuantity()).isEqualTo(1);
        }

        @Test
        @DisplayName("adds a separate line when the SKU differs")
        void doesNotMergeDifferentSku() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-B", 1);
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(2);
        }

        @Test
        @DisplayName("skips a source line already linked to this order")
        void skipsAlreadyLinkedLine() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine existing = line(order, "SKU-A", 2);
            existing.setSourceId("EST-1");
            existing.setSourceLineId("L1");
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.ESTIMATE, "EST-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 2, "30.00", "L1")));

            service.linkSource(ORDER_ID, "ESTIMATE", "EST-1");

            assertThat(order.getLines()).hasSize(1);
            assertThat(existing.getQuantity()).isEqualTo(2);
        }

        @Test
        @DisplayName("refuses to link a WORKORDER source before a customer is assigned")
        void refusesWorkorderWithoutCustomer() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.linkSource(ORDER_ID, "WORKORDER", "WO-1"))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("a customer must be assigned");
        }

        @Test
        @DisplayName("allows a WORKORDER source once a customer is assigned")
        void allowsWorkorderWithCustomer() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setCustomerId(CUSTOMER_ID);
            givenOrder(order);
            when(sourceDocumentPort.fetchLines(SourceType.WORKORDER, "WO-1"))
                    .thenReturn(List.of(sourceLine("SKU-A", 1, "30.00", "L1")));

            service.linkSource(ORDER_ID, "WORKORDER", "WO-1");

            assertThat(order.getLines()).hasSize(1);
        }

        @Test
        @DisplayName("rejects an unknown source type and an unknown order")
        void rejectsUnresolvableTargets() {
            givenOrder(order(SalesOrderStatus.DRAFT));
            assertThatThrownBy(() -> service.linkSource(ORDER_ID, "NOT_A_TYPE", "X-1"))
                    .isInstanceOf(SalesOrderRequestValidationException.class);

            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.linkSource(ORDER_ID, "ESTIMATE", "EST-1"))
                    .isInstanceOf(SalesOrderNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("order discounts")
    class OrderDiscounts {

        @Test
        @DisplayName("applies a percent discount")
        void appliesPercentDiscount() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            givenOrder(order);

            service.applyOrderDiscount(ORDER_ID, new OrderDiscountCommand("PERCENT", new BigDecimal("10"), "LOYALTY"));

            assertThat(order.getOrderDiscountType()).isEqualTo(OrderDiscountType.PERCENT);
            assertThat(order.getOrderDiscountValue()).isEqualByComparingTo("10.0000");
            assertThat(order.getOrderDiscountReasonCode()).isEqualTo("LOYALTY");
            verify(totalsCalculator).recalculate(order);
        }

        @Test
        @DisplayName("applies an amount discount above the percent ceiling")
        void appliesAmountDiscount() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            givenOrder(order);

            service.applyOrderDiscount(ORDER_ID, new OrderDiscountCommand("AMOUNT", new BigDecimal("250"), null));

            assertThat(order.getOrderDiscountType()).isEqualTo(OrderDiscountType.AMOUNT);
            assertThat(order.getOrderDiscountReasonCode()).isNull();
        }

        @Test
        @DisplayName("accepts a percent discount of exactly 100")
        void acceptsFullPercentDiscount() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            givenOrder(order);

            service.applyOrderDiscount(ORDER_ID, new OrderDiscountCommand("PERCENT", new BigDecimal("100"), null));

            assertThat(order.getOrderDiscountValue()).isEqualByComparingTo("100.0000");
        }

        @Test
        @DisplayName("rejects an unknown discount type")
        void rejectsUnknownType() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.applyOrderDiscount(
                            ORDER_ID, new OrderDiscountCommand("BOGUS", BigDecimal.TEN, null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("must be PERCENT or AMOUNT");
        }

        @Test
        @DisplayName("rejects a non-positive value and a percent above 100")
        void rejectsInvalidValues() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.applyOrderDiscount(
                            ORDER_ID, new OrderDiscountCommand("AMOUNT", BigDecimal.ZERO, null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("must be positive");

            assertThatThrownBy(() -> service.applyOrderDiscount(
                            ORDER_ID, new OrderDiscountCommand("PERCENT", new BigDecimal("101"), null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("and <= 100");
        }

        @Test
        @DisplayName("clears the discount and its reason")
        void clearsDiscount() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setOrderDiscountType(OrderDiscountType.PERCENT);
            order.setOrderDiscountValue(new BigDecimal("10"));
            order.setOrderDiscountReasonCode("LOYALTY");
            givenOrder(order);

            service.clearOrderDiscount(ORDER_ID);

            assertThat(order.getOrderDiscountType()).isNull();
            assertThat(order.getOrderDiscountValue()).isNull();
            assertThat(order.getOrderDiscountReasonCode()).isNull();
            verify(totalsCalculator).recalculate(order);
        }
    }

    @Nested
    @DisplayName("quote and reopen")
    class QuoteAndReopen {

        @Test
        @DisplayName("reprices, taxes, and stamps a quote expiry from the configured validity")
        void quotesCart() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-1", 2);
            givenOrder(order);

            service.quote(ORDER_ID);

            assertThat(order.getQuoteExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
            assertThat(order.getUpdatedBy()).isEqualTo("jane.smith");
            verify(orderTaxService).recomputeTax(order);
            verify(orderStateMachine).transition(order, SalesOrderStatus.QUOTED, "counter quote");
        }

        @Test
        @DisplayName("refuses to quote an empty cart")
        void refusesEmptyCart() {
            givenOrder(order(SalesOrderStatus.DRAFT));

            assertThatThrownBy(() -> service.quote(ORDER_ID))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("Cannot quote an empty cart");
        }

        @Test
        @DisplayName("refuses to quote an order linked to a workorder directly")
        void refusesDirectWorkorderLink() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setWorkOrderId(WORKORDER_ID);
            line(order, "SKU-1", 1);
            givenOrder(order);

            assertThatThrownBy(() -> service.quote(ORDER_ID))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("QUOTE_NOT_ALLOWED_FOR_WORKORDER");
        }

        @Test
        @DisplayName("refuses to quote an order carrying imported workorder lines")
        void refusesImportedWorkorderLines() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine imported = line(order, "SKU-1", 1);
            imported.setSourceType(SourceType.WORKORDER);
            givenOrder(order);

            assertThatThrownBy(() -> service.quote(ORDER_ID))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("QUOTE_NOT_ALLOWED_FOR_WORKORDER");
        }

        @Test
        @DisplayName("fails the quote rather than finalizing on stale prices")
        void failsQuoteOnUnavailablePricing() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            line(order, "SKU-1", 1);
            givenOrder(order);
            when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(PricingQuote.unavailable());

            assertThatThrownBy(() -> service.quote(ORDER_ID))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("cannot finalize a quote on stale prices");
        }

        @Test
        @DisplayName("leaves manual and source-document prices untouched when repricing")
        void repriceSkipsProtectedLines() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            SalesOrderLine manual = line(order, "SKU-MANUAL", 1);
            manual.setPriceSource(PriceSource.MANUAL);
            manual.setUnitPrice(new BigDecimal("5.0000"));
            SalesOrderLine sourced = SalesOrderLine.builder()
                    .orderLineId(NEW_LINE_ID)
                    .order(order)
                    .itemSku("SKU-SOURCED")
                    .itemDescription("Imported")
                    .quantity(1)
                    .unitPrice(new BigDecimal("7.0000"))
                    .priceSource(PriceSource.SOURCE_DOCUMENT)
                    .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                    .sourceType(SourceType.ESTIMATE)
                    .serialNumbers(new ArrayList<>())
                    .build();
            order.getLines().add(sourced);
            givenOrder(order);

            service.quote(ORDER_ID);

            assertThat(manual.getUnitPrice()).isEqualByComparingTo("5.0000");
            assertThat(sourced.getUnitPrice()).isEqualByComparingTo("7.0000");
            verify(pricingPort, never()).quoteForSku(anyString(), anyInt(), any(), any());
        }

        @Test
        @DisplayName("reopening clears the expiry and reprices best-effort")
        void reopensQuote() {
            SalesOrder order = order(SalesOrderStatus.QUOTED);
            order.setQuoteExpiresAt(NOW.plus(Duration.ofDays(3)));
            line(order, "SKU-1", 1);
            givenOrder(order);

            service.reopenQuote(ORDER_ID);

            assertThat(order.getQuoteExpiresAt()).isNull();
            verify(orderStateMachine).transition(order, SalesOrderStatus.DRAFT, "quote reopened");
            verify(totalsCalculator).recalculate(order);
        }

        @Test
        @DisplayName("reopening keeps the old price when pricing is unavailable")
        void reopenToleratesUnavailablePricing() {
            SalesOrder order = order(SalesOrderStatus.QUOTED);
            SalesOrderLine existing = line(order, "SKU-1", 1);
            givenOrder(order);
            when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any())).thenReturn(PricingQuote.unavailable());

            service.reopenQuote(ORDER_ID);

            assertThat(existing.getUnitPrice()).isEqualByComparingTo("12.5000");
        }
    }

    @Nested
    @DisplayName("checkout")
    class Checkout {

        private SalesOrder checkoutReadyOrder() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setCustomerValidationStatus(CustomerValidationStatus.VALIDATED);
            line(order, "SKU-1", 2);
            return order;
        }

        @BeforeEach
        void stubInvoice() {
            when(invoicingPort.createInvoiceForOrder(any()))
                    .thenReturn(new InvoiceRef(INVOICE_ID, "INV-1", "OPEN", new BigDecimal("27.0000"), false));
        }

        @Test
        @DisplayName("freezes the cart, records the invoice, and sets the balance due")
        void checksOut() {
            SalesOrder order = checkoutReadyOrder();
            givenOrder(order);

            CheckoutResult result = service.checkout(ORDER_ID, "co-1", null);

            assertThat(result.replay()).isFalse();
            assertThat(order.getCheckoutIdempotencyKey()).isEqualTo("co-1");
            assertThat(order.getInvoiceId()).isEqualTo(INVOICE_ID);
            assertThat(order.getInvoiceNumber()).isEqualTo("INV-1");
            assertThat(order.getAmountPaid()).isEqualByComparingTo("0");
            assertThat(order.getBalanceDue()).isEqualByComparingTo("27.0000");
            verify(orderStateMachine).transition(order, SalesOrderStatus.PENDING_PAYMENT, "checkout");
            verify(orderTaxService).recomputeTax(order);
        }

        @Test
        @DisplayName("accepts an explicit DEFAULT tender")
        void acceptsDefaultTender() {
            givenOrder(checkoutReadyOrder());

            assertThat(service.checkout(ORDER_ID, "co-1", " default ").replay()).isFalse();
        }

        @Test
        @DisplayName("requires an idempotency key")
        void requiresIdempotencyKey() {
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "  ", null))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("Idempotency-Key is required");
        }

        @Test
        @DisplayName("rejects an unsupported tender type")
        void rejectsUnsupportedTender() {
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "CRYPTO"))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("Unsupported tenderType");
        }

        @Test
        @DisplayName("replays when the same key is presented to the same order")
        void replaysSameKey() {
            SalesOrder order = checkoutReadyOrder();
            order.setCheckoutIdempotencyKey("co-1");
            givenOrder(order);

            assertThat(service.checkout(ORDER_ID, "co-1", null).replay()).isTrue();
            verify(invoicingPort, never()).createInvoiceForOrder(any());
        }

        @Test
        @DisplayName("rejects a key already used to check out a different order")
        void rejectsKeyOwnedByAnotherOrder() {
            givenOrder(checkoutReadyOrder());
            SalesOrder other = order(SalesOrderStatus.PENDING_PAYMENT);
            other.setOrderId(OTHER_ORDER_ID);
            when(salesOrderRepository.findByCheckoutIdempotencyKey("co-1")).thenReturn(Optional.of(other));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", null))
                    .isInstanceOf(CartIdempotencyConflictException.class)
                    .hasMessageContaining("a different order");
        }

        @Test
        @DisplayName("refuses to check out an empty cart")
        void refusesEmptyCart() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setCustomerValidationStatus(CustomerValidationStatus.VALIDATED);
            givenOrder(order);

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", null))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("Cannot check out an empty cart");
        }

        @Test
        @DisplayName("hard-blocks checkout while customer validation is pending")
        void blocksPendingValidation() {
            SalesOrder order = checkoutReadyOrder();
            order.setCustomerValidationStatus(CustomerValidationStatus.PENDING);
            givenOrder(order);

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", null))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("Customer validation is pending");
        }

        @Test
        @DisplayName("admits a short line as a backorder instead of refusing checkout")
        void admitsShortLineAsBackorder() {
            // CAP #1315: checkout used to throw on the first short line. A counter sale is real
            // even when the shelf is empty, so the shortfall becomes a promise to fulfil rather
            // than a lost sale — pos-inventory's backorder machinery exists for exactly this.
            SalesOrder order = checkoutReadyOrder();
            givenOrder(order);
            when(inventoryPort.checkAvailability(anyString(), any(), any()))
                    .thenReturn(new InventoryResult(false, BigDecimal.valueOf(0)));

            CheckoutResult result = service.checkout(ORDER_ID, "co-1", null);

            assertThat(result.replay()).isFalse();
            assertThat(order.getLines().get(0).getFulfillmentStatus()).isEqualTo(FulfillmentStatus.BACKORDER);
            verify(invoicingPort).createInvoiceForOrder(any());
        }

        @Test
        @DisplayName("labels a covered line AVAILABLE from the replica answer")
        void labelsCoveredLineAvailable() {
            SalesOrder order = checkoutReadyOrder();
            order.getLines().get(0).setFulfillmentStatus(FulfillmentStatus.BACKORDER);
            givenOrder(order);
            when(inventoryPort.checkAvailability(anyString(), any(), any()))
                    .thenReturn(new InventoryResult(true, BigDecimal.valueOf(10)));

            service.checkout(ORDER_ID, "co-1", null);

            // Re-evaluated at checkout, not trusted from line-add: stock moves in between.
            assertThat(order.getLines().get(0).getFulfillmentStatus()).isEqualTo(FulfillmentStatus.AVAILABLE);
        }

        @Test
        @DisplayName("requires one serial per unit for a serial-tracked SKU")
        void requiresSerialsForSerialTracked() {
            givenOrder(checkoutReadyOrder());
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku("SKU-1")
                            .active(true)
                            .trackingLevel("SERIAL")
                            .build()));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", null))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("is serial-tracked");
        }

        @Test
        @DisplayName("requires at least one lot reference for a lot-tracked SKU")
        void requiresLotReference() {
            givenOrder(checkoutReadyOrder());
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku("SKU-1")
                            .active(true)
                            .trackingLevel("LOT")
                            .build()));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", null))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("is lot-tracked");
        }

        @Test
        @DisplayName("accepts a serial-tracked line whose serials match the quantity")
        void acceptsMatchingSerials() {
            SalesOrder order = checkoutReadyOrder();
            order.getLines().get(0).setSerialNumbers(new ArrayList<>(List.of("S1", "S2")));
            givenOrder(order);
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku("SKU-1")
                            .active(true)
                            .trackingLevel("SERIAL")
                            .build()));

            assertThat(service.checkout(ORDER_ID, "co-1", null).replay()).isFalse();
        }

        @Test
        @DisplayName("exempts source-document lines from serial enforcement")
        void exemptsSourcedLinesFromSerialRules() {
            SalesOrder order = checkoutReadyOrder();
            order.getLines().get(0).setSourceType(SourceType.ESTIMATE);
            givenOrder(order);
            when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue("SKU-1"))
                    .thenReturn(Optional.of(ExtProduct.builder()
                            .sku("SKU-1")
                            .active(true)
                            .trackingLevel("SERIAL")
                            .build()));

            assertThat(service.checkout(ORDER_ID, "co-1", null).replay()).isFalse();
        }

        @Test
        @DisplayName("carries deposit provenance onto the invoice request")
        void carriesDepositProvenance() {
            SalesOrder order = checkoutReadyOrder();
            order.setDepositSourceType("ESTIMATE");
            order.setDepositSourceId(WORKORDER_ID);
            givenOrder(order);

            service.checkout(ORDER_ID, "co-1", null);

            ArgumentCaptor<com.positivity.shared.dto.OrderInvoiceCreationRequest> request =
                    ArgumentCaptor.forClass(com.positivity.shared.dto.OrderInvoiceCreationRequest.class);
            verify(invoicingPort).createInvoiceForOrder(request.capture());
            assertThat(request.getValue().getDepositSourceType()).isEqualTo("ESTIMATE");
            assertThat(request.getValue().getDepositSourceId()).isEqualTo(WORKORDER_ID);
            assertThat(request.getValue().getDepositAmount()).isEqualByComparingTo("27.0000");
        }

        @Test
        @DisplayName("omits deposit fields for an ordinary sale")
        void omitsDepositFieldsForOrdinarySale() {
            givenOrder(checkoutReadyOrder());

            service.checkout(ORDER_ID, "co-1", null);

            ArgumentCaptor<com.positivity.shared.dto.OrderInvoiceCreationRequest> request =
                    ArgumentCaptor.forClass(com.positivity.shared.dto.OrderInvoiceCreationRequest.class);
            verify(invoicingPort).createInvoiceForOrder(request.capture());
            assertThat(request.getValue().getDepositSourceType()).isNull();
            assertThat(request.getValue().getDepositAmount()).isNull();
        }
    }

    @Nested
    @DisplayName("ON_ACCOUNT checkout")
    class OnAccountCheckout {

        private SalesOrder onAccountOrder() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setCustomerId(CUSTOMER_ID);
            order.setCustomerValidationStatus(CustomerValidationStatus.VALIDATED);
            line(order, "SKU-1", 2);
            return order;
        }

        @BeforeEach
        void stubEligibleCustomer() {
            when(invoicingPort.createInvoiceForOrder(any()))
                    .thenReturn(new InvoiceRef(INVOICE_ID, "INV-1", "OPEN", new BigDecimal("27.0000"), false));
            when(extCustomerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtCustomer.builder()
                            .partyId(CUSTOMER_ID)
                            .partyType("COMMERCIAL")
                            .requirementsMet(true)
                            .build()));
            when(extBillingRulesRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtBillingRules.builder()
                            .partyId(CUSTOMER_ID)
                            .paymentTerms("NET30")
                            .creditHold(false)
                            .build()));
        }

        @Test
        @DisplayName("settles the order against the accepted invoice and completes it")
        void settlesOnAccount() {
            SalesOrder order = onAccountOrder();
            givenOrder(order);

            service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT");

            assertThat(order.getAmountPaid()).isEqualByComparingTo("27.0000");
            assertThat(order.getBalanceDue()).isEqualByComparingTo("0");
            verify(orderStateMachine).transition(order, SalesOrderStatus.COMPLETED, "on-account invoice accepted");

            ArgumentCaptor<OrderPaymentRecord> record = ArgumentCaptor.forClass(OrderPaymentRecord.class);
            verify(paymentRecordRepository).save(record.capture());
            assertThat(record.getValue().getRecordType()).isEqualTo(OrderPaymentRecord.RecordType.SETTLED);
            assertThat(record.getValue().getMethodType()).isEqualTo("ON_ACCOUNT");
            assertThat(record.getValue().getReference()).isEqualTo("INV-1");
            assertThat(record.getValue().getOccurredAt()).isEqualTo(NOW);
            verify(domainEventPublisher).publishOrderCompleted(eq(order), any());
        }

        @Test
        @DisplayName("requires the charge-on-account permission")
        void requiresPermission() {
            authenticate(MANUAL_PRICE_AUTHORITY);
            givenOrder(onAccountOrder());

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageContaining("order:order:charge_on_account");
        }

        @Test
        @DisplayName("requires a validated customer on the order")
        void requiresValidatedCustomer() {
            SalesOrder noCustomer = onAccountOrder();
            noCustomer.setCustomerId(null);
            givenOrder(noCustomer);
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("requires a validated customer");

            SalesOrder unvalidated = onAccountOrder();
            unvalidated.setCustomerValidationStatus(null);
            givenOrder(unvalidated);
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("requires a validated customer");
        }

        @Test
        @DisplayName("requires a commercial customer account")
        void requiresCommercialAccount() {
            givenOrder(onAccountOrder());
            when(extCustomerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtCustomer.builder()
                            .partyId(CUSTOMER_ID)
                            .partyType("RETAIL")
                            .requirementsMet(true)
                            .build()));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("requires a commercial customer account");
        }

        @Test
        @DisplayName("rejects a customer missing from the replica")
        void rejectsMissingCustomerReplica() {
            givenOrder(onAccountOrder());
            when(extCustomerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("requires a commercial customer account");
        }

        @Test
        @DisplayName("rejects an account that does not meet requirements")
        void rejectsRequirementsNotMet() {
            givenOrder(onAccountOrder());
            when(extCustomerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtCustomer.builder()
                            .partyId(CUSTOMER_ID)
                            .partyType("COMMERCIAL")
                            .requirementsMet(false)
                            .build()));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("does not currently meet requirements");
        }

        @Test
        @DisplayName("requires configured payment terms")
        void requiresPaymentTerms() {
            givenOrder(onAccountOrder());
            when(extBillingRulesRepository.findById(CUSTOMER_ID)).thenReturn(Optional.empty());
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("no billing terms configured");

            when(extBillingRulesRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtBillingRules.builder()
                            .partyId(CUSTOMER_ID)
                            .paymentTerms("  ")
                            .build()));
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("no billing terms configured");
        }

        @Test
        @DisplayName("rejects an account on credit hold")
        void rejectsCreditHold() {
            givenOrder(onAccountOrder());
            when(extBillingRulesRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(ExtBillingRules.builder()
                            .partyId(CUSTOMER_ID)
                            .paymentTerms("NET30")
                            .creditHold(true)
                            .build()));

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "co-1", "ON_ACCOUNT"))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("credit hold");
        }
    }

    @Nested
    @DisplayName("voidOrder")
    class VoidOrder {

        @Test
        @DisplayName("voids an unsettled order and stamps the actor")
        void voidsOrder() {
            SalesOrder order = order(SalesOrderStatus.PENDING_PAYMENT);
            givenOrder(order);

            service.voidOrder(ORDER_ID, "customer left");

            verify(orderStateMachine).transition(order, SalesOrderStatus.VOIDED, "customer left");
            assertThat(order.getUpdatedBy()).isEqualTo("jane.smith");
            verify(invoicingPort, never()).cancelInvoice(any());
        }

        @Test
        @DisplayName("cancels the invoice when the order carries one")
        void cancelsInvoice() {
            SalesOrder order = order(SalesOrderStatus.PENDING_PAYMENT);
            order.setInvoiceId(INVOICE_ID);
            givenOrder(order);

            service.voidOrder(ORDER_ID, null);

            verify(invoicingPort).cancelInvoice(INVOICE_ID);
            verify(orderStateMachine).transition(order, SalesOrderStatus.VOIDED, null);
        }

        @Test
        @DisplayName("is a no-op for an already voided order")
        void alreadyVoidedIsNoop() {
            SalesOrder order = order(SalesOrderStatus.VOIDED);
            givenOrder(order);

            service.voidOrder(ORDER_ID, "again");

            verify(orderStateMachine, never()).transition(any(), any(), any());
            verify(salesOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("blocks a void once money has settled — that reversal belongs to the cancellation saga")
        void blocksVoidAfterSettlement() {
            givenOrder(order(SalesOrderStatus.PENDING_PAYMENT));
            when(paymentRecordRepository.findByOrderId(ORDER_ID))
                    .thenReturn(List.of(OrderPaymentRecord.builder()
                            .orderId(ORDER_ID)
                            .recordType(OrderPaymentRecord.RecordType.SETTLED)
                            .methodType("CASH")
                            .amount(new BigDecimal("27.0000"))
                            .build()));

            assertThatThrownBy(() -> service.voidOrder(ORDER_ID, "too late"))
                    .isInstanceOf(OrderVoidBlockedException.class);
            verify(orderStateMachine, never()).transition(any(), any(), any());
        }

        @Test
        @DisplayName("rejects an unknown order")
        void rejectsUnknownOrder() {
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.voidOrder(ORDER_ID, "reason"))
                    .isInstanceOf(SalesOrderNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("summary mapping")
    class SummaryMapping {

        @Test
        @DisplayName("maps the null-safe fields of an untouched cart")
        void mapsMinimalOrder() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.setSubtotal(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
            givenOrder(order);

            SalesOrderSummary summary = service.getOrder(ORDER_ID);

            assertThat(summary.customerId()).isNull();
            assertThat(summary.vehicleId()).isNull();
            assertThat(summary.customerValidationStatus()).isNull();
            assertThat(summary.orderDiscountType()).isNull();
            assertThat(summary.invoiceId()).isNull();
            assertThat(summary.lines()).isEmpty();
        }

        @Test
        @DisplayName("maps every populated field, including line provenance")
        void mapsPopulatedOrder() {
            SalesOrder order = order(SalesOrderStatus.QUOTED);
            order.setCustomerId(CUSTOMER_ID);
            order.setVehicleId(VEHICLE_ID);
            order.setCustomerValidationStatus(CustomerValidationStatus.VALIDATED);
            order.setOrderDiscountType(OrderDiscountType.PERCENT);
            order.setOrderDiscountValue(new BigDecimal("10"));
            order.setInvoiceId(INVOICE_ID);
            order.setInvoiceNumber("INV-1");
            SalesOrderLine sourced = line(order, "SKU-1", 2);
            sourced.setSourceType(SourceType.ESTIMATE);
            sourced.setSourceId("EST-1");
            sourced.setSourceLineId("L1");
            sourced.setSerialNumbers(new ArrayList<>(List.of("S1")));
            givenOrder(order);

            SalesOrderSummary summary = service.getOrder(ORDER_ID);

            assertThat(summary.customerId()).isEqualTo(CUSTOMER_ID.toString());
            assertThat(summary.vehicleId()).isEqualTo(VEHICLE_ID.toString());
            assertThat(summary.customerValidationStatus()).isEqualTo(CustomerValidationStatus.VALIDATED.name());
            assertThat(summary.orderDiscountType()).isEqualTo(OrderDiscountType.PERCENT.name());
            assertThat(summary.invoiceId()).isEqualTo(INVOICE_ID.toString());
            assertThat(summary.lines()).singleElement().satisfies(mapped -> {
                assertThat(mapped.sourceType()).isEqualTo(SourceType.ESTIMATE.name());
                assertThat(mapped.sourceId()).isEqualTo("EST-1");
                assertThat(mapped.sourceLineId()).isEqualTo("L1");
                assertThat(mapped.serialNumbers()).containsExactly("S1");
            });
        }

        @Test
        @DisplayName("tolerates a null line in the collection")
        void tolerantOfNullLines() {
            SalesOrder order = order(SalesOrderStatus.DRAFT);
            order.getLines().add(null);
            givenOrder(order);

            assertThat(service.getOrder(ORDER_ID).lines()).isEmpty();
        }
    }
}
