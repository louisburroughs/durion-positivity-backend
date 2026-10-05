package com.positivity.order.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
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
import com.positivity.order.internal.config.InventoryCommandPublisher;
import com.positivity.order.internal.config.OrderDomainEventPublisher;
import com.positivity.order.internal.entity.CustomerValidationStatus;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.FulfillmentStatus;
import com.positivity.order.internal.entity.PriceSource;
import com.positivity.order.internal.entity.SalesOrder;
import com.positivity.order.internal.entity.SalesOrderLine;
import com.positivity.order.internal.entity.SalesOrderStatus;
import com.positivity.order.internal.entity.SourceType;
import com.positivity.order.internal.exception.InvalidCustomerException;
import com.positivity.order.internal.exception.OrderCustomerRequiredException;
import com.positivity.order.internal.exception.OrderNotEditableException;
import com.positivity.order.internal.exception.SalesOrderNotFoundException;
import com.positivity.order.internal.exception.SalesOrderRequestValidationException;
import com.positivity.order.internal.exception.SalesOrderUnprocessableException;
import com.positivity.order.internal.exception.WalkInNotAllowedException;
import com.positivity.order.internal.exception.WalkInNotPaidInFullException;
import com.positivity.order.internal.exception.WalkInUnavailableException;
import com.positivity.order.internal.repository.ExtBillingRulesRepository;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ExtProductRepository;
import com.positivity.order.internal.repository.OrderPaymentRecordRepository;
import com.positivity.order.internal.repository.OrderStatusHistoryRepository;
import com.positivity.order.internal.repository.RegisterSessionRepository;
import com.positivity.order.internal.repository.SalesOrderLineRepository;
import com.positivity.order.internal.repository.SalesOrderRepository;
import com.positivity.order.internal.service.model.CheckoutResult;
import com.positivity.order.internal.service.model.CreateCartCommand;
import com.positivity.order.internal.service.model.SalesOrderSummary;
import com.positivity.order.internal.service.model.SetCartCustomerCommand;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.security.common.LocationScope;
import com.positivity.shared.dto.OrderInvoiceCreationRequest;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * CAP:550 S8 (#2506, decisions AW12/AW13): checkout requires a customer, and the tenant's CASH
 * house account ("Walk-in customer") is usable only by explicit choice and only for a sale paid in
 * full now. Covers the set-customer command, the checkout guards in their stated order, the
 * {@code linkSource} refusal and the summary's {@code walkIn} / {@code customerDisplayName}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SalesOrderServiceImpl — customer required, walk-in only when paid in full (CAP:550 S8)")
class SalesOrderWalkInTest {

    private static final UUID ORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8001");
    private static final UUID HOUSE_ACCOUNT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8002");
    private static final UUID CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8003");
    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8004");
    private static final UUID VEHICLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8005");
    private static final UUID INVOICE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8006");
    private static final UUID WORKORDER_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8007");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8008");

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
    private OrderNumberService orderNumberService;

    @Mock
    private OrderStatusHistoryRepository orderStatusHistoryRepository;

    @Mock
    private OrderTaxService orderTaxService;

    @Mock
    private ObjectProvider<InventoryCommandPublisher> inventoryCommandPublisherProvider;

    @Mock
    private ObjectProvider<MeterRegistry> meterRegistryProvider;

    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();

    private SalesOrderServiceImpl service;

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
                new OrderStateMachine(orderStatusHistoryRepository, Clock.systemUTC()),
                orderNumberService,
                new OrderTotalsCalculator(),
                orderTaxService,
                new HouseAccountReplica(extCustomerRepository),
                inventoryCommandPublisherProvider,
                meterRegistryProvider,
                Clock.systemUTC());
        when(meterRegistryProvider.getIfAvailable()).thenReturn(meterRegistry);
        when(salesOrderRepository.save(any())).thenAnswer(inv -> {
            SalesOrder saved = inv.getArgument(0);
            if (saved.getOrderId() == null) {
                saved.setOrderId(ORDER_ID);
            }
            return saved;
        });
        when(salesOrderLineRepository.save(any())).thenAnswer(inv -> {
            SalesOrderLine saved = inv.getArgument(0);
            if (saved.getOrderLineId() == null) {
                saved.setOrderLineId(UUID.randomUUID());
            }
            return saved;
        });
        when(salesOrderRepository.findByCheckoutIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(extProductRepository.findFirstBySkuIgnoreCaseAndActiveTrue(anyString()))
                .thenReturn(Optional.empty());
        when(customerPort.lookupCustomer(any())).thenReturn(CustomerLookupResult.FOUND);
        when(customerPort.lookupVehicle(any(), any())).thenReturn(CustomerLookupResult.FOUND);
        when(inventoryPort.checkAvailability(anyString(), any(), any()))
                .thenReturn(new InventoryResult(true, BigDecimal.valueOf(999)));
        when(pricingPort.quoteForSku(anyString(), anyInt(), any(), any()))
                .thenReturn(new PricingQuote(
                        PricingQuote.Status.PRICED, money("78.12"), UUID.randomUUID(), "Widget", null, null));
        when(invoicingPort.createInvoiceForOrder(any()))
                .thenReturn(new InvoiceRef(INVOICE_ID, "INV-1", "DRAFT", money("84.37"), false));
        when(extCustomerRepository.findById(CUSTOMER_ID)).thenReturn(Optional.of(customer(CUSTOMER_ID, "Fleet Co")));
        when(extCustomerRepository.findById(OTHER_CUSTOMER_ID))
                .thenReturn(Optional.of(customer(OTHER_CUSTOMER_ID, "Jane Smith")));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    private static BigDecimal money(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static ExtCustomer customer(UUID partyId, String displayName) {
        return ExtCustomer.builder()
                .partyId(partyId)
                .status("ACTIVE")
                .displayName(displayName)
                .partyType("PERSON")
                .requirementsMet(true)
                .build();
    }

    private static ExtCustomer houseAccount() {
        ExtCustomer house = customer(HOUSE_ACCOUNT_ID, "Walk-in customer");
        house.setPartyType("COMMERCIAL");
        house.setHouseAccount(CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE);
        return house;
    }

    /** The replica holds the tenant's provisioned, active CASH house account. */
    private void givenHouseAccountProvisioned() {
        when(extCustomerRepository.findById(HOUSE_ACCOUNT_ID)).thenReturn(Optional.of(houseAccount()));
        when(extCustomerRepository.findFirstByHouseAccountAndStatusOrderByPartyIdAsc(
                        CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE, "ACTIVE"))
                .thenReturn(Optional.of(houseAccount()));
    }

    private SalesOrder cart(UUID customerId) {
        SalesOrder order = SalesOrder.builder()
                .orderId(ORDER_ID)
                .orderNumber("SO-TEST-0001")
                .locationId(LOCATION_ID)
                .customerId(customerId)
                .customerValidationStatus(customerId == null ? null : CustomerValidationStatus.VALIDATED)
                .clerkId("clerk-1")
                .terminalId("terminal-1")
                .status(SalesOrderStatus.DRAFT)
                .subtotal(money("78.12"))
                .grandTotal(money("78.12"))
                .lines(new ArrayList<>())
                .createdBy("clerk-1")
                .updatedBy("clerk-1")
                .build();
        order.getLines()
                .add(SalesOrderLine.builder()
                        .orderLineId(UUID.randomUUID())
                        .order(order)
                        .itemSku("SKU-1")
                        .itemDescription("Widget")
                        .quantity(1)
                        .unitPrice(money("78.12"))
                        .lineSubtotal(money("78.12"))
                        .taxAmount(money("0"))
                        .lineTotal(money("78.12"))
                        .priceSource(PriceSource.PRICING_SERVICE)
                        .fulfillmentStatus(FulfillmentStatus.AVAILABLE)
                        .build());
        when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.of(order));
        return order;
    }

    private SalesOrder walkInCart() {
        givenHouseAccountProvisioned();
        return cart(HOUSE_ACCOUNT_ID);
    }

    private static void linkWorkorderLine(SalesOrder order) {
        order.getLines().get(0).setSourceType(SourceType.WORKORDER);
        order.getLines().get(0).setSourceId(WORKORDER_ID.toString());
        order.getLines().get(0).setSourceLineId("L1");
    }

    /** The final tax pass lands the order on {@code grandTotal}, as pos-tax would. */
    private void givenFinalTaxMakesGrandTotal(String grandTotal) {
        doAnswer(inv -> {
                    SalesOrder order = inv.getArgument(0);
                    order.setTaxTotal(money(grandTotal).subtract(order.getSubtotal()));
                    order.setGrandTotal(money(grandTotal));
                    order.setTaxStale(false);
                    return null;
                })
                .when(orderTaxService)
                .recomputeTax(any());
    }

    private double refusedCount(String code) {
        var counter = meterRegistry
                .find(SalesOrderServiceImpl.CHECKOUT_REFUSED_COUNTER)
                .tag("code", code)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private static SetCartCustomerCommand named(UUID customerId) {
        return new SetCartCustomerCommand(customerId, false, null);
    }

    private static SetCartCustomerCommand walkIn() {
        return new SetCartCustomerCommand(null, true, null);
    }

    // ── PUT /v1/orders/carts/{orderId}/customer ──────────────────────────────

    @Nested
    @DisplayName("setCartCustomer")
    class SetCartCustomer {

        @Test
        @DisplayName("named customer: validated as at creation, vehicle cleared when absent, name served")
        void setsNamedCustomer() {
            SalesOrder order = cart(null);
            order.setVehicleId(VEHICLE_ID);

            SalesOrderSummary summary = service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID));

            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(order.getVehicleId()).as("vehicle cleared when absent").isNull();
            assertThat(order.getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.VALIDATED);
            assertThat(order.isTaxStale()).isTrue();
            assertThat(summary.customerId()).isEqualTo(CUSTOMER_ID.toString());
            assertThat(summary.walkIn()).isFalse();
            assertThat(summary.customerDisplayName()).isEqualTo("Fleet Co");
            verify(extCustomerRepository, never()).findFirstByHouseAccountAndStatusOrderByPartyIdAsc(any(), any());
        }

        @Test
        @DisplayName("named customer with a vehicle: the vehicle is validated against that customer")
        void setsNamedCustomerWithVehicle() {
            SalesOrder order = cart(null);

            service.setCartCustomer(ORDER_ID, new SetCartCustomerCommand(CUSTOMER_ID, false, VEHICLE_ID));

            assertThat(order.getVehicleId()).isEqualTo(VEHICLE_ID);
            verify(customerPort).lookupVehicle(CUSTOMER_ID, VEHICLE_ID);
        }

        @Test
        @DisplayName("unknown customer or vehicle → ORDER_INVALID_CUSTOMER, cart untouched")
        void refusesUnknownCustomerOrVehicle() {
            SalesOrder order = cart(null);
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.NOT_FOUND);

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID)))
                    .isInstanceOf(InvalidCustomerException.class);

            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.FOUND);
            when(customerPort.lookupVehicle(CUSTOMER_ID, VEHICLE_ID)).thenReturn(CustomerLookupResult.NOT_FOUND);
            assertThatThrownBy(() -> service.setCartCustomer(
                            ORDER_ID, new SetCartCustomerCommand(CUSTOMER_ID, false, VEHICLE_ID)))
                    .isInstanceOf(InvalidCustomerException.class);

            assertThat(order.getCustomerId()).isNull();
            verify(salesOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("cold replica: the customer is recorded as PENDING, as at creation")
        void coldReplicaIsPending() {
            SalesOrder order = cart(null);
            when(customerPort.lookupCustomer(CUSTOMER_ID)).thenReturn(CustomerLookupResult.UNAVAILABLE);

            service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID));

            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(order.getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.PENDING);
        }

        @Test
        @DisplayName("AC2: walkIn → the house account is the customer, walkIn = true, display name served")
        void setsWalkIn() {
            givenHouseAccountProvisioned();
            SalesOrder order = cart(null);

            SalesOrderSummary summary = service.setCartCustomer(ORDER_ID, walkIn());

            assertThat(order.getCustomerId()).isEqualTo(HOUSE_ACCOUNT_ID);
            assertThat(order.getCustomerValidationStatus()).isEqualTo(CustomerValidationStatus.VALIDATED);
            assertThat(summary.customerId()).isEqualTo(HOUSE_ACCOUNT_ID.toString());
            assertThat(summary.walkIn()).isTrue();
            assertThat(summary.customerDisplayName()).isEqualTo("Walk-in customer");
        }

        @Test
        @DisplayName("AC9: no house-account row in the replica → ORDER_WALK_IN_UNAVAILABLE, nothing substituted")
        void walkInUnavailable() {
            SalesOrder order = cart(null);
            when(extCustomerRepository.findFirstByHouseAccountAndStatusOrderByPartyIdAsc(any(), any()))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, walkIn()))
                    .isInstanceOf(WalkInUnavailableException.class);

            assertThat(order.getCustomerId()).isNull();
            verify(salesOrderRepository, never()).save(any());
            // Resolution is by the flag and the ACTIVE status only — never a name or a number.
            verify(extCustomerRepository)
                    .findFirstByHouseAccountAndStatusOrderByPartyIdAsc(
                            CustomerPartyUpdatedV1.HOUSE_ACCOUNT_CASH_SALE, "ACTIVE");
        }

        @Test
        @DisplayName("a row named like the walk-in customer but without the flag is an ordinary customer")
        void nameAloneIsNotWalkIn() {
            when(extCustomerRepository.findById(CUSTOMER_ID))
                    .thenReturn(Optional.of(customer(CUSTOMER_ID, "Walk-in customer")));
            cart(null);

            SalesOrderSummary summary = service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID));

            assertThat(summary.walkIn()).isFalse();
        }

        @Test
        @DisplayName("exactly one of customerId or walkIn: both, neither, or vehicleId with walkIn → 400")
        void requiresExactlyOneChoice() {
            cart(null);

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, new SetCartCustomerCommand(null, false, null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class);
            assertThatThrownBy(() ->
                            service.setCartCustomer(ORDER_ID, new SetCartCustomerCommand(CUSTOMER_ID, true, null)))
                    .isInstanceOf(SalesOrderRequestValidationException.class);
            assertThatThrownBy(
                            () -> service.setCartCustomer(ORDER_ID, new SetCartCustomerCommand(null, true, VEHICLE_ID)))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("vehicleId");
            verify(salesOrderRepository, never()).save(any());
        }

        @Test
        @DisplayName("a cashier changes from Walk-in to a named customer and back while DRAFT")
        void changesBetweenWalkInAndNamed() {
            SalesOrder order = walkInCart();

            assertThat(service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID)).walkIn())
                    .isFalse();
            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);

            assertThat(service.setCartCustomer(ORDER_ID, walkIn()).walkIn()).isTrue();
            assertThat(order.getCustomerId()).isEqualTo(HOUSE_ACCOUNT_ID);
        }

        @Test
        @DisplayName("DRAFT only: a QUOTED or checked-out order → ORDER_NOT_EDITABLE")
        void draftOnly() {
            SalesOrder order = cart(null);
            order.setStatus(SalesOrderStatus.QUOTED);
            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID)))
                    .isInstanceOf(OrderNotEditableException.class);

            order.setStatus(SalesOrderStatus.PENDING_PAYMENT);
            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, walkIn()))
                    .isInstanceOf(OrderNotEditableException.class);
            assertThat(order.getCustomerId()).isNull();
        }

        @Test
        @DisplayName("unknown order → ORDER_NOT_FOUND")
        void unknownOrder() {
            when(salesOrderRepository.findById(ORDER_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(CUSTOMER_ID)))
                    .isInstanceOf(SalesOrderNotFoundException.class);
        }

        @Test
        @DisplayName("workorder-locked: a linked WORKORDER source keeps the cart's customer")
        void workorderLinkedCartKeepsItsCustomer() {
            SalesOrder order = cart(CUSTOMER_ID);
            linkWorkorderLine(order);

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(OTHER_CUSTOMER_ID)))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("WORKORDER");
            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);

            // Naming the same customer again is not a change (and may set the vehicle).
            service.setCartCustomer(ORDER_ID, new SetCartCustomerCommand(CUSTOMER_ID, false, VEHICLE_ID));
            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
            assertThat(order.getVehicleId()).isEqualTo(VEHICLE_ID);
        }

        @Test
        @DisplayName("workorder-locked by the order's own workOrderId, too")
        void workorderIdLocksTheCustomer() {
            SalesOrder order = cart(CUSTOMER_ID);
            order.setWorkOrderId(WORKORDER_ID);

            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(OTHER_CUSTOMER_ID)))
                    .isInstanceOf(SalesOrderUnprocessableException.class);
        }

        @Test
        @DisplayName("AC4 at the PUT: Walk-in on a workorder-linked cart → WORKORDER_LINK")
        void walkInRefusedOnWorkorderLinkedCart() {
            givenHouseAccountProvisioned();
            SalesOrder order = cart(CUSTOMER_ID);
            linkWorkorderLine(order);

            WalkInNotAllowedException refusal = catchThrowableOfType(
                    WalkInNotAllowedException.class, () -> service.setCartCustomer(ORDER_ID, walkIn()));

            assertThat(refusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.WORKORDER_LINK);
            assertThat(order.getCustomerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("AC4 at the PUT: Walk-in on a deposit-take cart → DEPOSIT")
        void walkInRefusedOnDepositTakeCart() {
            givenHouseAccountProvisioned();
            SalesOrder order = cart(null);
            order.setDepositSourceType("ESTIMATE");
            order.setDepositSourceId(UUID.randomUUID());

            WalkInNotAllowedException refusal = catchThrowableOfType(
                    WalkInNotAllowedException.class, () -> service.setCartCustomer(ORDER_ID, walkIn()));

            assertThat(refusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.DEPOSIT);
            assertThat(order.getCustomerId()).isNull();
        }

        @Test
        @DisplayName("the house account named by customerId is a walk-in cart and held to the same rules")
        void explicitHouseAccountIdIsWalkIn() {
            givenHouseAccountProvisioned();
            SalesOrder order = cart(null);

            assertThat(service.setCartCustomer(ORDER_ID, named(HOUSE_ACCOUNT_ID))
                            .walkIn())
                    .isTrue();

            order.setCustomerId(null);
            order.setDepositSourceType("ESTIMATE");
            order.setDepositSourceId(UUID.randomUUID());
            assertThatThrownBy(() -> service.setCartCustomer(ORDER_ID, named(HOUSE_ACCOUNT_ID)))
                    .isInstanceOf(WalkInNotAllowedException.class);
        }
    }

    // ── POST /v1/orders/{orderId}/checkout ───────────────────────────────────

    @Nested
    @DisplayName("checkout")
    class Checkout {

        @Test
        @DisplayName("AC1: no customer → ORDER_CUSTOMER_REQUIRED, order stays DRAFT, no invoice, no demand")
        void refusesCartWithoutCustomer() {
            SalesOrder order = cart(null);

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("500.00")))
                    .isInstanceOf(OrderCustomerRequiredException.class)
                    .hasMessage("Choose a customer before taking payment");

            assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
            assertThat(order.getCheckoutIdempotencyKey()).isNull();
            verifyNoInteractions(invoicingPort);
            // Refused before demand registration and before any reprice or tax call.
            verifyNoInteractions(inventoryPort, pricingPort, orderTaxService);
            verify(salesOrderRepository, never()).save(any());
            assertThat(refusedCount("ORDER_CUSTOMER_REQUIRED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the customer check precedes the PENDING-validation check; the empty-cart check precedes it")
        void guardOrder() {
            SalesOrder order = cart(null);
            order.setCustomerValidationStatus(CustomerValidationStatus.PENDING);
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, null))
                    .isInstanceOf(OrderCustomerRequiredException.class);

            order.getLines().clear();
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, null))
                    .isInstanceOf(SalesOrderUnprocessableException.class)
                    .hasMessageContaining("empty cart");

            SalesOrder pending = cart(CUSTOMER_ID);
            pending.setCustomerValidationStatus(CustomerValidationStatus.PENDING);
            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, null))
                    .isInstanceOf(InvalidCustomerException.class)
                    .hasMessageContaining("pending");
        }

        @Test
        @DisplayName("AC6: a cart created without a customer is never given the house account; checkout keeps refusing")
        void neverAssignsTheHouseAccountOnItsOwn() {
            givenHouseAccountProvisioned();
            // createCart consults the caller's location scope (ADR-0061): a pre-rollout, unscoped caller.
            var caller = new UsernamePasswordAuthenticationToken("clerk-1", "n/a", List.of());
            caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, LocationScope.unscoped()));
            SecurityContextHolder.getContext().setAuthentication(caller);
            when(orderNumberService.nextNumber(any())).thenReturn("SO-TEST-0002");
            ArgumentCaptor<SalesOrder> created = ArgumentCaptor.forClass(SalesOrder.class);

            SalesOrderSummary summary = service.createCart(new CreateCartCommand(
                            "clerk-1", "terminal-1", null, null, LOCATION_ID, null, null, null, null, null))
                    .summary();
            verify(salesOrderRepository).save(created.capture());
            assertThat(created.getValue().getCustomerId()).isNull();
            assertThat(summary.customerId()).isNull();
            assertThat(summary.walkIn()).isFalse();

            SalesOrder order = cart(null);
            for (String key : List.of("chk-1", "chk-2")) {
                assertThatThrownBy(() -> service.checkout(ORDER_ID, key, null, new BigDecimal("999.00")))
                        .isInstanceOf(OrderCustomerRequiredException.class);
            }
            assertThat(order.getCustomerId()).isNull();
            // The house account is only ever looked up by the explicit Walk-in command.
            verify(extCustomerRepository, never()).findFirstByHouseAccountAndStatusOrderByPartyIdAsc(any(), any());
        }

        @Test
        @DisplayName(
                "AC3: walk-in total 84.37 after final tax, tendered 80.00 → refused naming 84.37; 84.37 → checked out")
        void walkInMustBePaidInFull() {
            SalesOrder order = walkInCart();
            givenFinalTaxMakesGrandTotal("84.37");

            WalkInNotPaidInFullException refusal = catchThrowableOfType(
                    WalkInNotPaidInFullException.class,
                    () -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("80.00")));

            assertThat(refusal.getGrandTotal()).isEqualByComparingTo("84.37");
            assertThat(refusal.grandTotalDisplay()).isEqualTo("84.37");
            assertThat(refusal).hasMessageContaining("84.37").hasMessageContaining("80.00");
            assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
            assertThat(order.getCheckoutIdempotencyKey()).isNull();
            verifyNoInteractions(invoicingPort);
            assertThat(refusedCount("ORDER_WALK_IN_NOT_PAID_IN_FULL")).isEqualTo(1.0);

            // The cashier re-tenders and retries with a new Idempotency-Key.
            CheckoutResult result = service.checkout(ORDER_ID, "chk-2", null, new BigDecimal("84.37"));

            assertThat(result.replay()).isFalse();
            assertThat(result.summary().status()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
            assertThat(result.summary().walkIn()).isTrue();
            ArgumentCaptor<OrderInvoiceCreationRequest> invoice =
                    ArgumentCaptor.forClass(OrderInvoiceCreationRequest.class);
            verify(invoicingPort).createInvoiceForOrder(invoice.capture());
            assertThat(invoice.getValue().getCustomerId()).isEqualTo(HOUSE_ACCOUNT_ID);
            assertThat(invoice.getValue().getTotalAmount()).isEqualByComparingTo("84.37");
        }

        @Test
        @DisplayName("the tender is compared after the final reprice and tax, with the server's total")
        void tenderComparedAfterFinalRepriceAndTax() {
            SalesOrder order = walkInCart();
            // The cart showed 78.12 before checkout; the final pass makes it 84.37.
            assertThat(order.getGrandTotal()).isEqualByComparingTo("78.12");
            givenFinalTaxMakesGrandTotal("84.37");

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("78.12")))
                    .isInstanceOf(WalkInNotPaidInFullException.class)
                    .hasMessageContaining("84.37");

            verify(pricingPort).quoteForSku(anyString(), anyInt(), any(), any());
            verify(orderTaxService).recomputeTax(order);
        }

        @Test
        @DisplayName("an absent tendered amount on a walk-in cart is refused; a larger one is accepted")
        void absentTenderRefusedLargerAccepted() {
            walkInCart();
            givenFinalTaxMakesGrandTotal("84.37");

            WalkInNotPaidInFullException refusal = catchThrowableOfType(
                    WalkInNotPaidInFullException.class, () -> service.checkout(ORDER_ID, "chk-1", null, null));
            assertThat(refusal.getTenderedAmount()).isNull();
            assertThat(refusal).hasMessageContaining("84.37");

            // Cash handed over may exceed the total (change is given at the drawer).
            assertThat(service.checkout(ORDER_ID, "chk-2", null, new BigDecimal("100"))
                            .summary()
                            .status())
                    .isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
        }

        @Test
        @DisplayName("the tender is compared at cent scale: a sub-cent residue in the 4-dp total is not short")
        void tenderComparedAtCentScale() {
            walkInCart();
            // 10 % off 78.12 leaves 76.1040 at the calculator's 4-dp scale; the register shows 76.10.
            givenFinalTaxMakesGrandTotal("76.1040");

            WalkInNotPaidInFullException refusal = catchThrowableOfType(
                    WalkInNotPaidInFullException.class,
                    () -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("76.09")));
            assertThat(refusal.grandTotalDisplay()).isEqualTo("76.10");
            assertThat(refusal.getGrandTotal()).isEqualByComparingTo("76.10");
            assertThat(refusal.getMessage()).contains("76.10").doesNotContain("76.104");

            assertThat(service.checkout(ORDER_ID, "chk-2", null, new BigDecimal("76.10"))
                            .summary()
                            .status())
                    .as("tendering the displayed cents is paid in full")
                    .isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
        }

        @Test
        @DisplayName("a whole-number total is named with two decimals")
        void wholeTotalNamedWithTwoDecimals() {
            walkInCart();
            givenFinalTaxMakesGrandTotal("80.0000");

            WalkInNotPaidInFullException refusal = catchThrowableOfType(
                    WalkInNotPaidInFullException.class, () -> service.checkout(ORDER_ID, "chk-1", null, null));
            assertThat(refusal.grandTotalDisplay()).isEqualTo("80.00");
        }

        @Test
        @DisplayName("for a cart with a registered customer the tendered amount is ignored")
        void tenderIgnoredForRegisteredCustomer() {
            cart(CUSTOMER_ID);
            givenFinalTaxMakesGrandTotal("84.37");

            assertThat(service.checkout(ORDER_ID, "chk-1", null, null).summary().status())
                    .isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());

            SalesOrder second = cart(CUSTOMER_ID);
            assertThat(service.checkout(ORDER_ID, "chk-2", null, BigDecimal.ZERO)
                            .summary()
                            .status())
                    .isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
            assertThat(second.getCustomerId()).isEqualTo(CUSTOMER_ID);
        }

        @Test
        @DisplayName("a negative tendered amount is a malformed request → ORDER_INVALID_ARGUMENT")
        void negativeTenderIsMalformed() {
            walkInCart();

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("-0.01")))
                    .isInstanceOf(SalesOrderRequestValidationException.class)
                    .hasMessageContaining("tenderedAmount");
        }

        @Test
        @DisplayName("AC4: walk-in + ON_ACCOUNT → ON_ACCOUNT, before the on-account gate and before demand")
        void walkInNeverOnAccount() {
            SalesOrder order = walkInCart();

            // No charge_on_account authority is granted: were the on-account gate reached first,
            // this would be an AccessDeniedException instead.
            WalkInNotAllowedException refusal = catchThrowableOfType(
                    WalkInNotAllowedException.class,
                    () -> service.checkout(ORDER_ID, "chk-1", "ON_ACCOUNT", new BigDecimal("84.37")));

            assertThat(refusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.ON_ACCOUNT);
            assertThat(order.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
            verifyNoInteractions(inventoryPort, invoicingPort, extBillingRulesRepository);
            assertThat(refusedCount("ORDER_WALK_IN_NOT_ALLOWED")).isEqualTo(1.0);
        }

        @Test
        @DisplayName("AC4: a walk-in deposit-take cart → DEPOSIT; a walk-in workorder-linked cart → WORKORDER_LINK")
        void walkInNeverDepositNorWorkorderLinked() {
            SalesOrder deposit = walkInCart();
            deposit.setDepositSourceType("ESTIMATE");
            deposit.setDepositSourceId(UUID.randomUUID());
            WalkInNotAllowedException depositRefusal = catchThrowableOfType(
                    WalkInNotAllowedException.class,
                    () -> service.checkout(ORDER_ID, "chk-1", null, new BigDecimal("84.37")));
            assertThat(depositRefusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.DEPOSIT);

            SalesOrder linked = walkInCart();
            linkWorkorderLine(linked);
            WalkInNotAllowedException linkRefusal = catchThrowableOfType(
                    WalkInNotAllowedException.class,
                    () -> service.checkout(ORDER_ID, "chk-2", null, new BigDecimal("84.37")));
            assertThat(linkRefusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.WORKORDER_LINK);

            verifyNoInteractions(inventoryPort, invoicingPort);
            assertThat(deposit.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
            assertThat(linked.getStatus()).isEqualTo(SalesOrderStatus.DRAFT);
        }

        @Test
        @DisplayName("a registered customer's deposit take and on-account sale are not walk-in refusals")
        void registeredCustomerIsNotHeldToWalkInRules() {
            SalesOrder deposit = cart(CUSTOMER_ID);
            deposit.setDepositSourceType("ESTIMATE");
            deposit.setDepositSourceId(UUID.randomUUID());

            assertThat(service.checkout(ORDER_ID, "chk-1", null, null).summary().status())
                    .isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
        }

        @Test
        @DisplayName("AC8: a replay with the same Idempotency-Key returns the stored result; no rule is re-evaluated")
        void replayReturnsStoredResult() {
            // An order checked out before go-live: no customer, already PENDING_PAYMENT (AW13).
            SalesOrder order = cart(null);
            order.setStatus(SalesOrderStatus.PENDING_PAYMENT);
            order.setCheckoutIdempotencyKey("chk-1");

            CheckoutResult replay = service.checkout(ORDER_ID, "chk-1", null, null);

            assertThat(replay.replay()).isTrue();
            assertThat(replay.summary().status()).isEqualTo(SalesOrderStatus.PENDING_PAYMENT.name());
            verifyNoInteractions(invoicingPort, inventoryPort, orderTaxService);
            assertThat(refusedCount("ORDER_CUSTOMER_REQUIRED")).isZero();

            // Likewise a walk-in order whose replay declares no tender at all, or a negative one:
            // the stored result comes back before the tender is looked at.
            SalesOrder walkIn = walkInCart();
            walkIn.setStatus(SalesOrderStatus.PENDING_PAYMENT);
            walkIn.setCheckoutIdempotencyKey("chk-2");
            assertThat(service.checkout(ORDER_ID, "chk-2", "ON_ACCOUNT", null).replay())
                    .isTrue();
            assertThat(service.checkout(ORDER_ID, "chk-2", null, new BigDecimal("-1"))
                            .replay())
                    .as("a replayed key returns the stored 200 even with a negative tenderedAmount")
                    .isTrue();
        }

        @Test
        @DisplayName("refusals are not counted when no meter registry is present")
        void toleratesAbsentMeterRegistry() {
            when(meterRegistryProvider.getIfAvailable()).thenReturn(null);
            cart(null);

            assertThatThrownBy(() -> service.checkout(ORDER_ID, "chk-1", null, null))
                    .isInstanceOf(OrderCustomerRequiredException.class);
        }
    }

    // ── PATCH /v1/orders/carts/{orderId}/source ──────────────────────────────

    @Nested
    @DisplayName("linkSource")
    class LinkSource {

        @Test
        @DisplayName("AC4 at linkSource: a WORKORDER onto a walk-in cart → WORKORDER_LINK, nothing imported")
        void workorderRefusedOnWalkInCart() {
            SalesOrder order = walkInCart();

            WalkInNotAllowedException refusal = catchThrowableOfType(
                    WalkInNotAllowedException.class,
                    () -> service.linkSource(ORDER_ID, "WORKORDER", WORKORDER_ID.toString()));

            assertThat(refusal.getReason()).isEqualTo(WalkInNotAllowedException.Reason.WORKORDER_LINK);
            assertThat(order.getLines()).hasSize(1);
            verifyNoInteractions(sourceDocumentPort);
        }

        @Test
        @DisplayName("an ESTIMATE may still be linked to a walk-in cart; a WORKORDER to a registered customer's")
        void otherLinksUnaffected() {
            SalesOrder walkIn = walkInCart();
            when(sourceDocumentPort.fetchLines(any(), anyString()))
                    .thenReturn(List.of(new SourceDocumentLine("SKU-9", "Imported", 1, money("10.00"), "L9", true)));

            service.linkSource(ORDER_ID, "ESTIMATE", UUID.randomUUID().toString());
            assertThat(walkIn.getLines()).hasSize(2);

            SalesOrder registered = cart(CUSTOMER_ID);
            service.linkSource(ORDER_ID, "WORKORDER", WORKORDER_ID.toString());
            assertThat(registered.getLines()).hasSize(2);
        }

        @Test
        @DisplayName("a WORKORDER link without any customer keeps its existing ORDER_UNPROCESSABLE refusal")
        void noCustomerKeepsExistingRefusal() {
            cart(null);

            assertThatThrownBy(() -> service.linkSource(ORDER_ID, "WORKORDER", WORKORDER_ID.toString()))
                    .isInstanceOf(SalesOrderUnprocessableException.class);
        }
    }

    // ── SalesOrderResponse.walkIn / customerDisplayName ──────────────────────

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("walkIn and customerDisplayName come from the replica; unknown customer → null name")
        void servesWalkInAndDisplayName() {
            walkInCart();
            SalesOrderSummary walkIn = service.getOrder(ORDER_ID);
            assertThat(walkIn.walkIn()).isTrue();
            assertThat(walkIn.customerDisplayName()).isEqualTo("Walk-in customer");

            cart(CUSTOMER_ID);
            SalesOrderSummary registered = service.getOrder(ORDER_ID);
            assertThat(registered.walkIn()).isFalse();
            assertThat(registered.customerDisplayName()).isEqualTo("Fleet Co");

            cart(UUID.randomUUID());
            SalesOrderSummary unknown = service.getOrder(ORDER_ID);
            assertThat(unknown.walkIn()).isFalse();
            assertThat(unknown.customerDisplayName()).isNull();

            cart(null);
            assertThat(service.getOrder(ORDER_ID).customerDisplayName()).isNull();
        }

        @Test
        @DisplayName("the cart list resolves names for the whole page in one replica read")
        void listResolvesNamesInOneRead() {
            SalesOrder walkIn = walkInCart();
            SalesOrder registered = cart(CUSTOMER_ID);
            SalesOrder anonymous = cart(null);
            when(salesOrderRepository.search(any(), any(), any(), any()))
                    .thenReturn(new PageImpl<>(List.of(walkIn, registered, anonymous)));
            when(extCustomerRepository.findAllById(any()))
                    .thenReturn(List.of(houseAccount(), customer(CUSTOMER_ID, "Fleet Co")));

            List<SalesOrderSummary> carts = service.listCarts(null, null, null, 0, 20);

            assertThat(carts).extracting(SalesOrderSummary::walkIn).containsExactly(true, false, false);
            assertThat(carts)
                    .extracting(SalesOrderSummary::customerDisplayName)
                    .containsExactly("Walk-in customer", "Fleet Co", null);
            verify(extCustomerRepository).findAllById(any());
            verify(extCustomerRepository, never()).findById(any());
        }
    }
}
