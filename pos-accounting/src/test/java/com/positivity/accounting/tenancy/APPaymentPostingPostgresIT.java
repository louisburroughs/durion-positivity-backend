package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.dto.APPaymentResponse;
import com.positivity.accounting.internal.dto.ExecuteAPPaymentRequest;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.enums.APPaymentStatus;
import com.positivity.accounting.internal.enums.PaymentMethod;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.AccountingPeriodClosedException;
import com.positivity.accounting.internal.exception.AccountingPeriodHardLockedException;
import com.positivity.accounting.internal.exception.CurrencyNotSupportedException;
import com.positivity.accounting.internal.exception.GLMappingNotConfiguredException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.handler.APPaymentGLPostingEventHandler;
import com.positivity.accounting.internal.payment.GatewayPaymentRequest;
import com.positivity.accounting.internal.payment.GatewayPaymentResponse;
import com.positivity.accounting.internal.payment.PaymentGatewayProvider;
import com.positivity.accounting.internal.repository.APPaymentRepository;
import com.positivity.accounting.internal.repository.EventOutboxRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.APPaymentService;
import com.positivity.accounting.internal.service.OutboxProcessor;
import com.positivity.accounting.internal.service.VendorBillApprovalService;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.security.common.GatewaySecurityConstants;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantContext;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * AP payments end to end on the full Flyway chain (CAP:550 S42, #2603, #2627; AW40, AW41): tenants provisioned from the
 * accounting template, so {@code AP_PAYMENT} resolves through S37's copy of the seed; bills received, matched and
 * approved through their own services; the pay command with a mocked gateway; the outbox delivered on the test thread
 * by the real handler, with no caller, as the outbox processor does; row-level security as {@code pos_app}.
 *
 * <p>Requires Docker.
 */
@DisplayName("AP payments post through AP_PAYMENT (#2603, #2627, real Postgres)")
@TestPropertySource(properties = "accounting.ap.lock-timeout=1s")
class APPaymentPostingPostgresIT extends PostgresTenancyTestBase {

    private static final String PAYER = "payer.pat";
    private static final String CONTROLLER = "controller.cfo";
    private static final String[] CONTROLLER_GRANTS = {
        "ROLE_CONTROLLER",
        "accounting:ap:view",
        "accounting:ap:approve",
        "accounting:ap:reject",
        "accounting:ap:approve_over_limit",
        "accounting:je:post"
    };
    private static final String OVERRIDE = "Supplier paid on the agreed date; the period closed early";

    /** The gateway: every charge succeeds unless a test says otherwise; its calls are counted. */
    @MockitoBean
    private PaymentGatewayProvider gateway;

    /** Deliveries run on the test thread, never from the 5-second poll. */
    @MockitoBean
    private OutboxProcessor outboxProcessor;

    @Autowired
    private APPaymentService payments;

    @Autowired
    private APPaymentGLPostingEventHandler outboxHandler;

    @Autowired
    private EventOutboxRepository outboxRows;

    @Autowired
    private APPaymentRepository paymentRows;

    @Autowired
    private VendorBillRepository billRows;

    @Autowired
    private VendorBillService vendorBills;

    @Autowired
    private VendorBillApprovalService approvals;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    private final List<UUID> tenants = new ArrayList<>();

    @BeforeEach
    void gatewaySucceeds() {
        reset(gateway);
        when(gateway.executePayment(any())).thenAnswer(invocation -> {
            GatewayPaymentRequest request = invocation.getArgument(0);
            String charge = "ch_" + request.getIdempotencyKey();
            return new GatewayPaymentResponse(
                    charge, PaymentGatewayProvider.GatewayPaymentStatus.SUCCEEDED, charge, null, "{}");
        });
    }

    @AfterEach
    void removeTestTenants() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
        JdbcTemplate owner = owner();
        List<String> scoped = owner.queryForList(
                "SELECT table_name FROM information_schema.columns WHERE table_schema = 'public'"
                        + " AND column_name = 'tenant_id' AND table_name NOT LIKE 'pg_%' ORDER BY table_name",
                String.class);
        for (UUID tenant : tenants) {
            owner.update("UPDATE ap_payment SET gl_journal_entry_id = NULL WHERE tenant_id = ?", tenant);
            owner.update(
                    "UPDATE journal_entry SET reversal_journal_entry_id = NULL, reversed_by_journal_entry_id = NULL"
                            + " WHERE tenant_id = ?",
                    tenant);
            owner.update("UPDATE vendor_bill SET journal_entry_id = NULL WHERE tenant_id = ?", tenant);
            for (int pass = 0; pass < 8; pass++) {
                boolean blocked = false;
                for (String table : scoped) {
                    try {
                        owner.update("DELETE FROM " + table + " WHERE tenant_id = ?", tenant);
                    } catch (RuntimeException stillReferenced) {
                        blocked = true;
                    }
                }
                if (!blocked) {
                    break;
                }
            }
        }
        tenants.clear();
    }

    // ---- the posting --------------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC1, AC9, S37: 412.00 + 1.50 fee from the tenant's one bank account → Dr 2000 412.00 / Dr 6030 1.50 /"
            + " Cr 1000 413.50 dated payment_date; the allocation posts nothing; 2000 = open bills − unapplied")
    void paysAndPostsOnce() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse paid = approvedBill(tenant, "400.00");
        VendorBillResponse open = approvedBill(tenant, "100.00");
        assertThat(mappedAccount(tenant, "ACCOUNTS_PAYABLE"))
                .as("S37 provisioned AP_PAYMENT")
                .isEqualTo("2000");
        assertThat(mappedAccount(tenant, "PAYMENT_FEES")).isEqualTo("6030");

        APPaymentResponse payment = pay(tenant, request(paid, "412.00", "1.50", "400.00"));

        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POST_PENDING);
        assertThat(payment.getPaymentDate()).isEqualTo(today());
        assertThat(payment.getBankAccountId()).isEqualTo(provisionedAccountId(tenant, "1000"));
        assertThat(payment.getUnappliedAmount()).isEqualByComparingTo("12.00");
        assertThat(count(tenant, "journal_entry", "source_event_type = 'AP_PAYMENT'"))
                .as("nothing posts before the outbox")
                .isZero();

        deliver(tenant, payment.getPaymentId());
        deliver(tenant, payment.getPaymentId()); // a second delivery posts nothing

        APPaymentResponse posted = read(tenant, payment.getPaymentId());
        assertThat(posted.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(count(tenant, "journal_entry", "source_event_type = 'AP_PAYMENT'"))
                .isEqualTo(1);
        assertThat(lines(tenant, posted.getGlJournalEntryId()))
                .containsExactly("2000 D412.00", "6030 D1.50", "1000 C413.50");
        assertThat(owner().queryForObject(
                                "SELECT transaction_date::date FROM journal_entry WHERE tenant_id = ? AND journal_entry_id = ?",
                                LocalDate.class,
                                tenant,
                                posted.getGlJournalEntryId()))
                .isEqualTo(today());
        // AW37: 2000 (credit) = Σ open amounts of approved bills − Σ unapplied AP payments = 100.00 − 12.00.
        assertThat(balance(tenant, "2000")).isEqualByComparingTo("88.00");
        assertThat(open.getTotalAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("AC3: a fee of 0.00 pays and posts with no PAYMENT_FEES mapping")
    void zeroFeeNeedsNoFeesMapping() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse bill = approvedBill(tenant, "250.00");
        endMapping(tenant, "PAYMENT_FEES");

        APPaymentResponse payment = pay(tenant, request(bill, "250.00", "0.00", "250.00"));
        deliver(tenant, payment.getPaymentId());

        assertThat(lines(tenant, read(tenant, payment.getPaymentId()).getGlJournalEntryId()))
                .containsExactly("2000 D250.00", "1000 C250.00");
    }

    // ---- the bank account ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC2: the one active USD BANK_CASH account is the default beside an inactive USD and an active CAD"
            + " one; with a second active USD account and none sent → 400 fieldErrors[bankAccountId], no charge")
    void defaultBankAccount() throws Exception {
        UUID tenant = tenant();
        bankAccount(tenant, "1010", true);
        UUID cad = bankAccount(tenant, "1020", false);
        bankProfile(tenant, cad, "CAD");
        VendorBillResponse bill = approvedBill(tenant, "50.00");

        APPaymentResponse payment = pay(tenant, request(bill, "50.00", null, "50.00"));
        assertThat(payment.getBankAccountId()).isEqualTo(provisionedAccountId(tenant, "1000"));

        bankAccount(tenant, "1030", false);
        VendorBillResponse second = approvedBill(tenant, "60.00");
        reset(gateway);
        assertThatThrownBy(() -> pay(tenant, request(second, "60.00", null, "60.00")))
                .isInstanceOfSatisfying(VendorBillException.class, refusal -> {
                    assertThat(refusal.getCode()).isEqualTo(VendorBillException.Code.VALIDATION_ERROR);
                    assertThat(refusal.getFieldErrors())
                            .extracting(VendorBillException.FieldError::field)
                            .containsExactly("bankAccountId");
                });
        verify(gateway, never()).executePayment(any());
        assertThat(count(tenant, "ap_payment", "true")).isEqualTo(1);
    }

    // ---- refusals before the gateway ----------------------------------------------------------------------------

    @Test
    @DisplayName("AC3: CREDIT_CARD, EUR, a hard-locked date, a closed period, a missing ACCOUNTS_PAYABLE or (with a"
            + " fee) PAYMENT_FEES mapping → 422, no gateway call, no ap_payment row")
    void refusedBeforeTheGateway() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse bill = approvedBill(tenant, "80.00");
        reset(gateway);

        ExecuteAPPaymentRequest card = request(bill, "80.00", null, "80.00");
        card.setPaymentMethod(PaymentMethod.CREDIT_CARD);
        assertThatThrownBy(() -> pay(tenant, card))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refusal -> assertThat(refusal.getCode())
                                .isEqualTo(VendorBillException.Code.AP_PAYMENT_METHOD_NOT_SUPPORTED));

        ExecuteAPPaymentRequest euros = request(bill, "80.00", null, "80.00");
        euros.setCurrency("EUR");
        assertThatThrownBy(() -> pay(tenant, euros)).isInstanceOf(CurrencyNotSupportedException.class);

        endMapping(tenant, "PAYMENT_FEES");
        assertThatThrownBy(() -> pay(tenant, request(bill, "80.00", "1.00", "80.00")))
                .isInstanceOfSatisfying(
                        GLMappingNotConfiguredException.class,
                        refusal -> assertThat(refusal.getReferenceId()).isEqualTo("AP_PAYMENT/PAYMENT_FEES"));
        endMapping(tenant, "ACCOUNTS_PAYABLE");
        assertThatThrownBy(() -> pay(tenant, request(bill, "80.00", null, "80.00")))
                .isInstanceOfSatisfying(
                        GLMappingNotConfiguredException.class,
                        refusal -> assertThat(refusal.getReferenceId()).isEqualTo("AP_PAYMENT/ACCOUNTS_PAYABLE"));

        closePeriod(tenant, today());
        assertThatThrownBy(() -> pay(tenant, request(bill, "80.00", null, "80.00")))
                .as("the closed period answers before the missing mapping")
                .isInstanceOf(AccountingPeriodClosedException.class);

        setting(tenant, "HARD_LOCK_DATE", today().plusDays(1).toString());
        assertThatThrownBy(() -> pay(tenant, request(bill, "80.00", null, "80.00")))
                .as("the hard lock answers before the closed period")
                .isInstanceOf(AccountingPeriodHardLockedException.class);

        verify(gateway, never()).executePayment(any());
        assertThat(count(tenant, "ap_payment", "true")).isZero();
        assertThat(count(tenant, "ap_payment_allocation", "true")).isZero();
        assertThat(billStatus(tenant, bill.getVendorBillId())).isEqualTo("APPROVED");
    }

    // ---- the period override ------------------------------------------------------------------------------------

    @Test
    @DisplayName("AC5, RLS: a closed period, an overrideJustification and accounting:period:override → the payment"
            + " executes; the outbox posting is dated in that period and its override audit row names the payer; another"
            + " tenant sees none of it")
    void overrideTravelsToThePosting() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse bill = approvedBill(tenant, "300.00");
        closePeriod(tenant, today());
        ExecuteAPPaymentRequest request = request(bill, "300.00", null, "300.00");
        request.setOverrideJustification(OVERRIDE);

        APPaymentResponse payment = pay(tenant, request, "accounting:ap:pay", "accounting:period:override");
        assertThat(owner().queryForMap(
                                "SELECT period_override_justification, period_override_by FROM ap_payment"
                                        + " WHERE tenant_id = ? AND payment_id = ?",
                                tenant,
                                payment.getPaymentId()))
                .containsEntry("period_override_justification", OVERRIDE)
                .containsEntry("period_override_by", PAYER);

        deliver(tenant, payment.getPaymentId());

        UUID entry = read(tenant, payment.getPaymentId()).getGlJournalEntryId();
        assertThat(entry).isNotNull();
        assertThat(owner().queryForMap(
                                "SELECT user_id, justification FROM accounting_audit_log WHERE tenant_id = ? AND entity_id = ?"
                                        + " AND operation = 'PERIOD_OVERRIDE_POST'",
                                tenant,
                                entry))
                .containsEntry("user_id", PAYER)
                .containsEntry("justification", OVERRIDE);

        UUID other = tenant();
        assertThat(asTenant(other, () -> paymentRows.findById(payment.getPaymentId())))
                .as("row-level security: another tenant does not see the payment or its override")
                .isEmpty();
        assertThat(asTenant(other, () -> payments.getPaymentById(payment.getPaymentId())))
                .isEmpty();
    }

    // ---- refused after execution, and the retry -----------------------------------------------------------------

    @Test
    @DisplayName("AC6: the mapping deactivated before the outbox → GL_POST_FAILED / GL_MAPPING_NOT_CONFIGURED, the"
            + " row completes; a retry before the fix → 422, still failed; after it → posted on payment_date; again →"
            + " 409 AP_PAYMENT_NOT_RETRYABLE")
    void refusedAfterExecutionThenRetried() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse bill = approvedBill(tenant, "412.00");
        APPaymentResponse payment = pay(tenant, request(bill, "412.00", "1.50", "412.00"));
        endMapping(tenant, "ACCOUNTS_PAYABLE");

        assertThatCode(() -> deliver(tenant, payment.getPaymentId()))
                .as("a refusal is not transient: the outbox row completes")
                .doesNotThrowAnyException();
        assertThat(paymentState(tenant, payment.getPaymentId()))
                .containsEntry("status", "GL_POST_FAILED")
                .containsEntry("gl_post_error", "GL_MAPPING_NOT_CONFIGURED");

        signIn(CONTROLLER, "accounting:je:post");
        assertThatThrownBy(() -> asTenant(tenant, () -> payments.retryGLPosting(payment.getPaymentId(), null)))
                .isInstanceOf(GLMappingNotConfiguredException.class);
        assertThat(paymentState(tenant, payment.getPaymentId()))
                .containsEntry("status", "GL_POST_FAILED")
                .containsEntry("gl_post_error", "GL_MAPPING_NOT_CONFIGURED");

        restoreMapping(tenant, "ACCOUNTS_PAYABLE");
        APPaymentResponse retried = asTenant(tenant, () -> payments.retryGLPosting(payment.getPaymentId(), null));
        assertThat(retried.getStatus()).isEqualTo(APPaymentStatus.GL_POSTED);
        assertThat(retried.getGlPostError()).isNull();
        assertThat(lines(tenant, retried.getGlJournalEntryId()))
                .containsExactly("2000 D412.00", "6030 D1.50", "1000 C413.50");
        assertThat(owner().queryForObject(
                                "SELECT transaction_date::date FROM journal_entry WHERE tenant_id = ? AND journal_entry_id = ?",
                                LocalDate.class,
                                tenant,
                                retried.getGlJournalEntryId()))
                .isEqualTo(payment.getPaymentDate());

        assertThatThrownBy(() -> asTenant(tenant, () -> payments.retryGLPosting(payment.getPaymentId(), null)))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refusal -> assertThat(refusal.getCode())
                                .isEqualTo(VendorBillException.Code.AP_PAYMENT_NOT_RETRYABLE));
        assertThat(count(tenant, "journal_entry", "source_event_type = 'AP_PAYMENT'"))
                .isEqualTo(1);
    }

    // ---- #2627: bounded lock waits ------------------------------------------------------------------------------

    @Test
    @DisplayName("AC11 (#2627): a vendor's bill held FOR UPDATE by another transaction → the payment fails after"
            + " lock_timeout (409 LOCK_TIMEOUT), the gateway is not called and nothing is persisted; an approval waiting"
            + " on a held bill fails the same way")
    void lockTimeout() throws Exception {
        UUID tenant = tenant();
        VendorBillResponse held = approvedBill(tenant, "150.00");
        VendorBillResponse awaiting = awaitingBill(tenant, "75.00");
        reset(gateway);

        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService holder = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = holder.submit(() -> asTenant(tenant, () -> {
                new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    billRows.lockById(held.getVendorBillId()).orElseThrow();
                    billRows.lockById(awaiting.getVendorBillId()).orElseThrow();
                    locked.countDown();
                    try {
                        release.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
                return null;
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> pay(tenant, request(held, "150.00", null, "150.00")))
                    .isInstanceOfAny(
                            PessimisticLockingFailureException.class,
                            LockTimeoutException.class,
                            PessimisticLockException.class);
            signIn(CONTROLLER, CONTROLLER_GRANTS);
            assertThatThrownBy(() -> asTenant(
                            tenant,
                            () -> approvals.approve(
                                    awaiting.getVendorBillId(),
                                    new VendorBillCommands.Approve("Checked against the delivery", null, null, null))))
                    .isInstanceOfAny(
                            PessimisticLockingFailureException.class,
                            LockTimeoutException.class,
                            PessimisticLockException.class);

            release.countDown();
            holding.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            holder.shutdownNow();
        }

        verify(gateway, never()).executePayment(any());
        assertThat(count(tenant, "ap_payment", "true")).isZero();
        assertThat(count(tenant, "ap_payment_allocation", "true")).isZero();
        assertThat(billStatus(tenant, awaiting.getVendorBillId())).isEqualTo("AWAITING_APPROVAL");

        // Once the holder is gone, the payment goes through.
        APPaymentResponse payment = pay(tenant, request(held, "150.00", null, "150.00"));
        assertThat(payment.getStatus()).isEqualTo(APPaymentStatus.GL_POST_PENDING);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private UUID tenant() {
        UUID tenant = tenantWithZone();
        tenants.add(tenant);
        provisionAccounting(tenant);
        return tenant;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }

    private APPaymentResponse pay(UUID tenant, ExecuteAPPaymentRequest request, String... grants) {
        signIn(PAYER, grants.length == 0 ? new String[] {"accounting:ap:pay"} : grants);
        try {
            return asTenant(tenant, () -> payments.executePayment(request, PAYER));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private APPaymentResponse read(UUID tenant, UUID paymentId) {
        return asTenant(tenant, () -> payments.getPaymentById(paymentId)).orElseThrow();
    }

    /** The outbox delivery of the payment's work item, as the outbox processor runs it: the row's tenant, no caller. */
    private void deliver(UUID tenant, UUID paymentId) throws Exception {
        SecurityContextHolder.clearContext();
        EventOutbox row = outboxRows.findAll().stream()
                .filter(outbox -> paymentId.equals(outbox.getAggregateId()))
                .findFirst()
                .orElseThrow();
        APPaymentGLPostingEvent event = objectMapper.readValue(row.getPayload(), APPaymentGLPostingEvent.class);
        assertThat(event.getPaymentId()).isEqualTo(paymentId);
        TenantContext.runAs(row.getTenantId(), () -> outboxHandler.onAPPaymentGLPosting(event));
        assertThat(row.getTenantId()).isEqualTo(tenant);
    }

    private static ExecuteAPPaymentRequest request(VendorBillResponse bill, String gross, String fee, String applied) {
        ExecuteAPPaymentRequest request = ExecuteAPPaymentRequest.builder()
                .paymentRef("PAY-" + UUIDv7Generator.generate())
                .vendorId(bill.getVendorId())
                .grossAmount(new BigDecimal(gross))
                .feeAmount(fee == null ? null : new BigDecimal(fee))
                .currency("USD")
                .paymentMethod(PaymentMethod.ACH)
                .build();
        request.setAllocations(List.of(
                new ExecuteAPPaymentRequest.AllocationLineRequest(bill.getVendorBillId(), new BigDecimal(applied))));
        return request;
    }

    /** A bill received, matched, and approved by the controller; the receiving clerk created it. */
    private VendorBillResponse approvedBill(UUID tenant, String amount) {
        VendorBillResponse bill = awaitingBill(tenant, amount);
        signIn(CONTROLLER, CONTROLLER_GRANTS);
        try {
            VendorBillResponse approved = asTenant(
                    tenant,
                    () -> approvals.approve(
                            bill.getVendorBillId(),
                            new VendorBillCommands.Approve("Checked against the delivery", null, null, null)));
            assertThat(approved.getStatus()).isEqualTo(VendorBillStatus.APPROVED);
            return approved;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    /** A goods receipt of one stocked line of {@code amount}, then the vendor's invoice of the same amount. */
    private VendorBillResponse awaitingBill(UUID tenant, String amount) {
        signIn("receiving.dock", "accounting:ap:view");
        try {
            UUID vendor = UUIDv7Generator.generate();
            UUID product = UUIDv7Generator.generate();
            LocalDate invoiced = today().minusDays(1);
            asTenant(
                    tenant,
                    () -> vendorBills.handleGoodsReceivedEvent(GoodsReceivedEvent.builder()
                            .eventId(UUIDv7Generator.generate())
                            .organizationId(UUIDv7Generator.generate())
                            .purchaseOrderId(UUIDv7Generator.generate())
                            .vendorId(vendor)
                            .vendorName("Acme Parts Co")
                            .receivedDate(invoiced.atTime(9, 30))
                            .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                                    .productId(product)
                                    .description("Brake pads")
                                    .quantity(BigDecimal.ONE)
                                    .unitPrice(new BigDecimal(amount))
                                    .isInventoryItem(true)
                                    .build()))
                            .build()));
            VendorBillResponse bill = asTenant(
                    tenant,
                    () -> vendorBills.handleVendorInvoiceReceivedEvent(VendorInvoiceReceivedEvent.builder()
                            .eventId(UUIDv7Generator.generate())
                            .organizationId(UUIDv7Generator.generate())
                            .vendorId(vendor)
                            .invoiceReference("INV-" + vendor.toString().substring(24))
                            .invoiceDate(invoiced.atStartOfDay())
                            .dueDate(invoiced.plusDays(30).atStartOfDay())
                            .lineItems(List.of(VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                                    .productId(product)
                                    .description("Brake pads")
                                    .quantity(BigDecimal.ONE)
                                    .unitPrice(new BigDecimal(amount))
                                    .build()))
                            .build()));
            assertThat(bill.getStatus()).isEqualTo(VendorBillStatus.AWAITING_APPROVAL);
            return bill;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void signIn(String username, String... authorities) {
        UsernamePasswordAuthenticationToken caller = new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                Stream.of(authorities).map(SimpleGrantedAuthority::new).toList());
        caller.setDetails(Map.of(GatewaySecurityConstants.DETAIL_USERNAME, username));
        SecurityContextHolder.getContext().setAuthentication(caller);
    }

    private static JdbcTemplate owner() {
        return new JdbcTemplate(ownerDataSource());
    }

    /** A BANK_CASH account of the tenant; {@code deactivated} gives it a deactivation date in the past. */
    private static UUID bankAccount(UUID tenant, String code, boolean deactivated) {
        UUID id = UUIDv7Generator.generate();
        owner().update(
                        "INSERT INTO gl_account (tenant_id, gl_account_id, account_code, account_name, account_type,"
                                + " account_subtype, reconcilable, deactivation_date, created_at, modified_at,"
                                + " created_by, modified_by, version) VALUES (?, ?, ?, ?, 'ASSET', 'BANK_CASH', TRUE, ?,"
                                + " TIMESTAMPTZ '2026-01-01 00:00:00+00', TIMESTAMPTZ '2026-01-01 00:00:00+00', 't',"
                                + " 't', 0)",
                        tenant,
                        id,
                        code,
                        "Bank " + code,
                        deactivated ? java.sql.Timestamp.valueOf("2026-01-01 00:00:00") : null);
        return id;
    }

    private static void bankProfile(UUID tenant, UUID glAccountId, String currency) {
        owner().update(
                        "INSERT INTO bank_account_profile (tenant_id, gl_account_id, currency, created_at, created_by,"
                                + " updated_at) VALUES (?, ?, ?, TIMESTAMPTZ '2026-01-01 00:00:00+00', 't',"
                                + " TIMESTAMPTZ '2026-01-01 00:00:00+00')",
                        tenant,
                        glAccountId,
                        currency);
    }

    /** Ends the tenant's {@code AP_PAYMENT/<key>} mapping before today: no mapping is effective on the payment date. */
    private void endMapping(UUID tenant, String key) {
        mappingEnd(tenant, key, java.sql.Timestamp.valueOf(today().minusDays(1).atStartOfDay()));
    }

    private static void restoreMapping(UUID tenant, String key) {
        mappingEnd(tenant, key, null);
    }

    private static void mappingEnd(UUID tenant, String key, java.sql.Timestamp end) {
        int updated = owner().update(
                        "UPDATE gl_mapping m SET effective_end_date = ? FROM mapping_key k, posting_category c"
                                + " WHERE m.tenant_id = ? AND k.tenant_id = m.tenant_id"
                                + " AND k.mapping_key_id = m.mapping_key_id AND c.tenant_id = m.tenant_id"
                                + " AND c.posting_category_id = m.posting_category_id"
                                + " AND c.category_name = 'AP_PAYMENT' AND k.key_name = ?",
                        end,
                        tenant,
                        key);
        assertThat(updated).isEqualTo(1);
    }

    private static String mappedAccount(UUID tenant, String key) {
        return owner().queryForObject(
                        "SELECT a.account_code FROM gl_mapping m JOIN mapping_key k ON k.tenant_id = m.tenant_id"
                                + " AND k.mapping_key_id = m.mapping_key_id JOIN posting_category c"
                                + " ON c.tenant_id = m.tenant_id AND c.posting_category_id = m.posting_category_id"
                                + " JOIN gl_account a ON a.tenant_id = m.tenant_id AND a.gl_account_id = m.gl_account_id"
                                + " WHERE m.tenant_id = ? AND c.category_name = 'AP_PAYMENT' AND k.key_name = ?",
                        String.class,
                        tenant,
                        key);
    }

    private static void closePeriod(UUID tenant, LocalDate inPeriod) {
        LocalDate start = inPeriod.withDayOfMonth(1);
        owner().update(
                        "INSERT INTO accounting_period (tenant_id, period_id, period_code, start_date, end_date,"
                                + " status, created_at, created_by, modified_at, modified_by, version) VALUES (?, ?, ?, ?,"
                                + " ?, 'CLOSED', TIMESTAMPTZ '2026-09-01 00:00:00+00', 't', TIMESTAMPTZ '2026-09-01"
                                + " 00:00:00+00', 't', 0) ON CONFLICT (tenant_id, period_code) DO UPDATE SET status ="
                                + " 'CLOSED'",
                        tenant,
                        UUIDv7Generator.generate(),
                        start.toString().substring(0, 7),
                        start,
                        start.plusMonths(1).minusDays(1));
    }

    private static void setting(UUID tenant, String key, String value) {
        owner().update(
                        "INSERT INTO accounting_configuration (tenant_id, config_id, config_key, config_value,"
                                + " created_at, created_by, modified_at, modified_by) VALUES (?, ?, ?, ?,"
                                + " TIMESTAMPTZ '2026-10-08 00:00:00+00', 't', TIMESTAMPTZ '2026-10-08 00:00:00+00', 't')",
                        tenant,
                        UUIDv7Generator.generate(),
                        key,
                        value);
    }

    private static Map<String, Object> paymentState(UUID tenant, UUID paymentId) {
        return owner().queryForMap(
                        "SELECT status, gl_post_error FROM ap_payment WHERE tenant_id = ? AND payment_id = ?",
                        tenant,
                        paymentId);
    }

    private static String billStatus(UUID tenant, UUID billId) {
        return owner().queryForObject(
                        "SELECT status FROM vendor_bill WHERE tenant_id = ? AND vendor_bill_id = ?",
                        String.class,
                        tenant,
                        billId);
    }

    /** Credit minus debit of the tenant's posted lines on {@code accountCode}. */
    private static BigDecimal balance(UUID tenant, String accountCode) {
        return owner().queryForObject(
                        "SELECT COALESCE(SUM(l.credit_amount - l.debit_amount), 0) FROM journal_entry_line l"
                                + " JOIN journal_entry e ON e.tenant_id = l.tenant_id"
                                + " AND e.journal_entry_id = l.journal_entry_id"
                                + " JOIN gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND g.account_code = ? AND e.status = 'POSTED'",
                        BigDecimal.class,
                        tenant,
                        accountCode);
    }

    /** "code D|C amount" for each line of the entry, in line order. */
    private static List<String> lines(UUID tenant, UUID entryId) {
        return owner().query(
                        "SELECT g.account_code, l.debit_amount, l.credit_amount FROM journal_entry_line l JOIN"
                                + " gl_account g ON g.tenant_id = l.tenant_id AND g.gl_account_id = l.gl_account_id"
                                + " WHERE l.tenant_id = ? AND l.journal_entry_id = ? ORDER BY l.line_number",
                        (rs, n) -> {
                            BigDecimal debit = rs.getBigDecimal(2);
                            return rs.getString(1) + " "
                                    + (debit != null && debit.signum() > 0
                                            ? "D" + debit.setScale(2).toPlainString()
                                            : "C"
                                                    + rs.getBigDecimal(3)
                                                            .setScale(2)
                                                            .toPlainString());
                        },
                        tenant,
                        entryId);
    }

    private static int count(UUID tenant, String table, String where) {
        return owner().queryForObject(
                        "SELECT count(*) FROM " + table + " WHERE tenant_id = ? AND " + where, Integer.class, tenant);
    }
}
