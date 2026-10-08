package com.positivity.accounting.tenancy;

import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_A;
import static com.positivity.tenancy.testing.TenantTestSupport.TENANT_B;
import static com.positivity.tenancy.testing.TenantTestSupport.asTenant;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.positivity.accounting.AccountingPostgresContainer;
import com.positivity.accounting.internal.config.LedgerCurrency;
import com.positivity.accounting.internal.dto.GoodsReceivedEvent;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorInvoiceReceivedEvent;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.entity.VendorBillLine;
import com.positivity.accounting.internal.entity.VendorBillNumbers;
import com.positivity.accounting.internal.entity.VendorBillNumbersTest;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.exception.VendorBillException;
import com.positivity.accounting.internal.repository.AccountingAuditLogRepository;
import com.positivity.accounting.internal.repository.AccountingSequenceRepository;
import com.positivity.accounting.internal.repository.ExtSupplierVendorRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.accounting.internal.repository.SupplierInvoiceHoldRepository;
import com.positivity.accounting.internal.repository.VendorBillLineRepository;
import com.positivity.accounting.internal.repository.VendorBillMatchCandidateRepository;
import com.positivity.accounting.internal.repository.VendorBillReissueRepository;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.AccountingSequenceLocker;
import com.positivity.accounting.internal.service.KafkaFactIngestionRecorder;
import com.positivity.accounting.internal.service.SupplierEventsListener;
import com.positivity.accounting.internal.service.SupplierVendorCopies;
import com.positivity.accounting.internal.service.VendorBillAutoApproval;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard;
import com.positivity.accounting.internal.service.VendorBillInvoiceMatcher;
import com.positivity.accounting.internal.service.VendorBillLocks;
import com.positivity.accounting.internal.service.VendorBillReader;
import com.positivity.accounting.internal.service.VendorBillService;
import com.positivity.accounting.internal.service.VendorBillServiceImpl;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * The vendor-bill duplicate rule against real Postgres and the real Flyway chain (#2501, CAP:550 S0;
 * ADR-0070 Decision 4): the V4 backfill expression against the Java normaliser, the partial unique
 * index, the rule's query under row-level security, the missing sequence, and the migration's guard.
 *
 * <p>The application pool is the non-owner {@code pos_app} role under strict tenancy; fixtures and
 * catalog reads go through the owner, as Flyway does. The listener is built by hand because the Kafka
 * rails stay off in the {@code pg} profile; the path from envelope JSON to bill is the production one.
 *
 * <p>Requires Docker.
 */
@DisplayName("Vendor bill duplicate rule (#2501, real Postgres)")
class VendorBillDuplicateRulePostgresIT extends PostgresTenancyTestBase {

    private static final String MIGRATION = "db/migration/V4__vendor_bill_duplicate_rule.sql";
    private static final String INDEX = "uq_vendor_bill_duplicate_rule";

    private static final LocalDateTime OCT_1_MORNING = LocalDateTime.of(2026, 10, 1, 9, 30);
    private static final LocalDateTime OCT_1_EVENING = LocalDateTime.of(2026, 10, 1, 17, 0);

    private final JdbcTemplate owner = new JdbcTemplate(ownerDataSource());

    @Autowired
    private VendorBillLocks locks;

    @Autowired
    private VendorBillAutoApproval autoApproval;

    @Autowired
    private Clock clock;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private VendorBillRepository bills;

    @Autowired
    private ExtSupplierVendorRepository vendorCopy;

    @Autowired
    private SupplierInvoiceHoldRepository holds;

    @Autowired
    private ObjectProvider<MeterRegistry> meterRegistry;

    @Autowired
    private LedgerCurrency ledgerCurrency;

    @Autowired
    private KafkaFactIngestionRecorder ingestionRecorder;

    @Autowired
    private VendorBillDuplicateGuard guard;

    @Autowired
    private VendorBillService vendorBillService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private VendorBillLineRepository billLines;

    @Autowired
    private VendorBillMatchCandidateRepository matchCandidates;

    @Autowired
    private VendorBillReissueRepository reissues;

    @Autowired
    private VendorBillInvoiceMatcher matcher;

    @Autowired
    private VendorBillReader reader;

    @Autowired
    private AccountingAuditLogRepository auditLogs;

    @Autowired
    private SupplierVendorCopies vendorCopies;

    @Autowired
    private AccountingSequenceLocker sequenceLocker;

    @Autowired
    private AccountingSequenceRepository sequences;

    private SupplierEventsListener listener;

    /** A vendor of this test's own, so nothing here meets another test's bills. */
    private UUID vendor;

    private final List<String> eventIds = new ArrayList<>();

    /** Every vendor a test wrote bills for, so that all of them are removed afterwards. */
    private final List<UUID> vendors = new ArrayList<>();

    @BeforeEach
    void setUp() {
        vendor = UUID.randomUUID();
        vendors.add(vendor);
        copyVendor(TENANT_A, vendor);
        clearBillCounters();
        listener = listener(guard);
    }

    @AfterEach
    void cleanUp() {
        for (UUID written : vendors) {
            owner.update(
                    "DELETE FROM accounting_event WHERE domain_key_id IN"
                            + " (SELECT vendor_bill_id::text FROM vendor_bill WHERE vendor_id = ?)",
                    written);
            for (String child : List.of(
                    "vendor_bill_line",
                    "vendor_bill_match_evidence",
                    "vendor_bill_match_candidate",
                    "vendor_bill_reissue")) {
                owner.update(
                        "DELETE FROM " + child + " WHERE vendor_bill_id IN"
                                + " (SELECT vendor_bill_id FROM vendor_bill WHERE vendor_id = ?)",
                        written);
            }
            owner.update("DELETE FROM vendor_bill WHERE vendor_id = ?", written);
            owner.update("DELETE FROM ext_supplier_vendor WHERE vendor_id = ?", written);
        }
        vendors.clear();
        for (String eventId : eventIds) {
            owner.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        }
        eventIds.clear();
        clearBillCounters();
    }

    /** The EDI listener as production wires it, over {@code duplicateGuard}. */
    private SupplierEventsListener listener(VendorBillDuplicateGuard duplicateGuard) {
        return new SupplierEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                bills,
                vendorCopy,
                holds,
                ledgerCurrency,
                ingestionRecorder,
                duplicateGuard,
                reissues,
                locks,
                meterRegistry,
                transactionManager);
    }

    /**
     * Puts {@code vendorId} in {@code tenant}'s copy of the vendor master, active and named Acme Tire (S24): a bill,
     * from either channel, must name a vendor in the copy.
     */
    private void copyVendor(UUID tenant, UUID vendorId) {
        owner.update(
                "INSERT INTO ext_supplier_vendor (tenant_id, vendor_id, vendor_number, display_name, status,"
                        + " remit_to_version, tax_registrations, created_by, aggregate_version, updated_at)"
                        + " VALUES (?, ?, ?, 'Acme Tire', 'ACTIVE', 0, '[]'::jsonb, 'buyer.ben', 1, now())",
                tenant,
                vendorId,
                "V-" + vendorId.toString().substring(0, 8));
    }

    /** Only this class numbers bills on the shared database, so its counters start from nothing each time. */
    private void clearBillCounters() {
        owner.update("DELETE FROM accounting_sequence WHERE scope_key LIKE 'BILL-%'");
    }

    // ---- criterion 1: the backfill expression is the Java normaliser's twin ----------------------

    static Stream<Arguments> br1Examples() {
        return VendorBillNumbersTest.br1Examples();
    }

    @ParameterizedTest(name = "\"{0}\" -> \"{1}\"")
    @MethodSource("br1Examples")
    @DisplayName("criterion 1: the V4 backfill expression gives every BR-1 key, as the Java normaliser does")
    void backfillExpressionMatchesTheJavaNormaliser(String billNumber, String key) throws IOException {
        String sqlKey = owner.queryForObject("SELECT " + backfillExpression(), String.class, billNumber);

        assertThat(sqlKey).isEqualTo(key);
        assertThat(VendorBillNumbers.normalise(billNumber)).isEqualTo(sqlKey);
    }

    /**
     * The expression V4 backfills with, read from the migration itself so the test cannot pass against
     * a copy: everything between {@code SET bill_number_key =} and the statement's end, with the
     * column swapped for a bind parameter.
     */
    private static String backfillExpression() throws IOException {
        String migration = new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8);
        Matcher statement =
                Pattern.compile("SET bill_number_key = (.*?);", Pattern.DOTALL).matcher(migration);
        assertThat(statement.find()).as("the backfill UPDATE in " + MIGRATION).isTrue();
        String expression = statement.group(1);
        assertThat(expression).contains("normalize(bill_number, NFKC)");
        return expression.replace("normalize(bill_number, NFKC)", "normalize(CAST(? AS text), NFKC)");
    }

    // ---- criterion 2: the index ------------------------------------------------------------------

    @Test
    @DisplayName("criterion 2: uq_vendor_bill_duplicate_rule is unique, tenant-led and excludes VOIDED and REJECTED")
    void indexExistsWithItsPredicate() {
        String definition = owner.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND tablename = 'vendor_bill'"
                        + " AND indexname = ?",
                String.class,
                INDEX);

        assertThat(definition)
                .startsWith("CREATE UNIQUE INDEX " + INDEX + " ON public.vendor_bill USING btree"
                        + " (tenant_id, vendor_id, bill_number_key, ((bill_date)::date)) WHERE ")
                .contains("status")
                .contains("'VOIDED'::text")
                .contains("'REJECTED'::text");
        assertThat(owner.queryForObject(
                        "SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'public'"
                                + " AND table_name = 'vendor_bill' AND column_name = 'bill_number_key'",
                        String.class))
                .isEqualTo("NO");
    }

    // ---- criterion 3: the database is the authority ----------------------------------------------

    @Test
    @DisplayName("criterion 3: a second live row with the same key and date is refused by the index, whatever wrote it")
    void indexRefusesADuplicateWrittenAroundTheService() {
        insertBill(TENANT_A, vendor, "INV-00123", OCT_1_MORNING, "APPROVED");

        assertThatThrownBy(() -> insertBill(TENANT_A, vendor, "inv/00123", OCT_1_EVENING, "PENDING_RECEIPT_MATCH"))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining(INDEX);

        // Not the rule: another date (BR-3), another key (zeros inside the key count), or a voided twin.
        insertBill(TENANT_A, vendor, "inv/00123", OCT_1_EVENING.plusDays(1), "PENDING_RECEIPT_MATCH");
        insertBill(TENANT_A, vendor, "INV-123", OCT_1_EVENING, "PENDING_RECEIPT_MATCH");
        insertBill(TENANT_A, vendor, "INV 00123", OCT_1_EVENING, "VOIDED");
        insertBill(TENANT_A, vendor, "INV.00123", OCT_1_EVENING, "REJECTED");
        assertThat(billCount(vendor)).isEqualTo(5);
    }

    @Test
    @DisplayName("criterion 3: a violation raised through JPA is recognised as the duplicate rule's")
    void jpaViolationIsRecognisedAsTheRules() {
        insertBill(TENANT_A, vendor, "INV-00123", OCT_1_MORNING, "APPROVED");

        assertThatThrownBy(() -> inTenant(TENANT_A, () -> bills.saveAndFlush(newBill("inv 00123", OCT_1_EVENING))))
                .isInstanceOf(DataIntegrityViolationException.class)
                .matches(VendorBillDuplicateGuard::isDuplicateRuleViolation, "names " + INDEX);
    }

    @Test
    @DisplayName("the rule's query finds the live original, and not the bill that is being renamed")
    void queryFindsTheLiveOriginalAndHonoursTheExclusion() {
        UUID original = insertBill(TENANT_A, vendor, "INV-00123", OCT_1_MORNING, "PAID");

        assertThat(inTenant(TENANT_A, () -> guard.findOriginal(vendor, "inv 00123", OCT_1_EVENING, null)))
                .map(VendorBill::getVendorBillId)
                .contains(original);
        assertThat(inTenant(TENANT_A, () -> guard.findOriginal(vendor, "inv 00123", OCT_1_EVENING, UUID.randomUUID())))
                .isPresent();
        assertThat(inTenant(TENANT_A, () -> guard.findOriginal(vendor, "inv 00123", OCT_1_EVENING, original)))
                .as("the bill itself is not its own duplicate")
                .isEmpty();
        assertThat(inTenant(TENANT_A, () -> guard.findOriginal(vendor, "inv 00123", OCT_1_EVENING.plusDays(1), null)))
                .as("another date is another bill")
                .isEmpty();
    }

    // ---- criteria 8, 10, 11: the EDI listener ----------------------------------------------------

    @Test
    @DisplayName("criterion 8: a fact for a live bill, written differently, creates no bill and is marked processed")
    void identicalFactForALiveBillCreatesNoBill() {
        UUID original = insertBill(TENANT_A, vendor, "INV-1", OCT_1_MORNING, "APPROVED");

        String eventId = deliver(TENANT_A, "inv-1", "2026-10-01", "100.00");

        assertThat(billCount(vendor)).isEqualTo(1);
        assertThat(status(original)).isEqualTo("APPROVED");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        assertThat(owner.queryForMap(
                        "SELECT status, idempotency_outcome FROM accounting_event WHERE domain_key_id = ?",
                        original.toString()))
                .containsEntry("status", "PROCESSED")
                .containsEntry("idempotency_outcome", "DUPLICATE_IGNORED");
    }

    @Test
    @DisplayName("AC14 (#2509): an APPROVED bill re-issued at another amount stays APPROVED; one exception item, its"
            + " created_at stamped by auditing (ADR-0024)")
    void reissueOfAnApprovedBillIsRecordedWithItsCreatedAt() {
        UUID original = insertBill(TENANT_A, vendor, "INV-1", OCT_1_MORNING, "APPROVED");

        deliver(TENANT_A, "inv-1", "2026-10-01", "120.00");

        assertThat(status(original)).isEqualTo("APPROVED");
        assertThat(billCount(vendor)).isEqualTo(1);
        List<Map<String, Object>> items = owner.queryForList(
                "SELECT vendor_bill_id, incoming_amount, held_amount, created_at FROM vendor_bill_reissue"
                        + " WHERE vendor_bill_id = ?",
                original);
        assertThat(items).singleElement().satisfies(item -> {
            assertThat((BigDecimal) item.get("incoming_amount")).isEqualByComparingTo("120.00");
            assertThat((BigDecimal) item.get("held_amount")).isEqualByComparingTo("100.00");
            assertThat(item.get("created_at")).isNotNull();
        });
    }

    @ParameterizedTest(name = "original {0}")
    @EnumSource(
            value = VendorBillStatus.class,
            names = {"VOIDED", "REJECTED"})
    @DisplayName("criterion 10: a fact for a voided or rejected bill becomes a new bill and leaves the old one alone")
    void reissueAfterAVoidOrRejectionBecomesANewBill(VendorBillStatus released) {
        UUID original = insertBill(TENANT_A, vendor, "INV-1", OCT_1_MORNING, released.name());
        owner.update(
                "UPDATE vendor_bill SET rejected_by = 'ap.clerk', rejection_reason = 'Wrong vendor',"
                        + " rejected_at = TIMESTAMPTZ '2026-10-02 08:00:00+00' WHERE vendor_bill_id = ?",
                original);

        deliver(TENANT_A, "INV-1", "2026-10-01", "100.00");

        List<Map<String, Object>> rows = owner.queryForList(
                "SELECT vendor_bill_id, status, bill_number_key, rejected_by, rejection_reason FROM vendor_bill"
                        + " WHERE vendor_id = ? ORDER BY created_at",
                vendor);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("vendor_bill_id", original)
                .containsEntry("status", released.name())
                .containsEntry("rejected_by", "ap.clerk")
                .containsEntry("rejection_reason", "Wrong vendor");
        assertThat(rows.get(1))
                .containsEntry("status", "PENDING_RECEIPT_MATCH")
                .containsEntry("bill_number_key", "INV1")
                .containsEntry("rejected_by", null);
    }

    @Test
    @DisplayName("criterion 11 (BR-3, G14): the same number a year later is a new bill")
    void sameNumberOnAnotherDateBecomesANewBill() {
        UUID lastYear = insertBill(TENANT_A, vendor, "INV-1", LocalDateTime.of(2025, 10, 1, 0, 0), "PAID");

        deliver(TENANT_A, "INV-1", "2026-10-01", "100.00");

        assertThat(billCount(vendor)).isEqualTo(2);
        assertThat(status(lastYear)).isEqualTo("PAID");
        assertThat(owner.queryForObject(
                        "SELECT status FROM vendor_bill WHERE vendor_id = ? AND bill_date = TIMESTAMP '2026-10-01"
                                + " 00:00:00'",
                        String.class,
                        vendor))
                .isEqualTo("PENDING_RECEIPT_MATCH");
    }

    // ---- criteria 4, 5, 14: the goods-receipt path -----------------------------------------------

    @Test
    @DisplayName(
            "criteria 14, 5, 4: Flyway alone is enough to number a bill; a replay returns the bill; a repeated number"
                    + " is refused")
    void goodsReceiptIsNumberedOnAFlywayOnlyDatabaseAndRefusesARepeatedNumber() {
        GoodsReceivedEvent first = goodsReceived(UUID.randomUUID());

        // One enclosing transaction, rolled back: the goods-receipt path also posts to the ledger, and
        // none of that belongs in the database the other tests share.
        rolledBack(TENANT_A, () -> {
            VendorBillResponse created = vendorBillService.handleGoodsReceivedEvent(first);
            // Criterion 14: this was a 500 while the number came from a database sequence that no
            // migration created. It is the tenant's first number of the month, from a counter row
            // nothing provisioned.
            assertThat(sequenceOf(created.getBillNumber())).isEqualTo(1L);

            // Criterion 5 (BR-8): the same eventId again is the same bill, never a duplicate.
            assertThat(vendorBillService.handleGoodsReceivedEvent(first).getVendorBillId())
                    .isEqualTo(created.getVendorBillId());

            // Criterion 4: the tenant's counter set back so the generated number repeats. Done in
            // this transaction, which holds the counter row's lock.
            sequences.findByScopeKey(billScope()).orElseThrow().setNextValue(1L);
            sequences.flush();
            assertThatThrownBy(() -> vendorBillService.handleGoodsReceivedEvent(goodsReceived(UUID.randomUUID())))
                    .isInstanceOfSatisfying(VendorBillDuplicateException.class, refused -> {
                        assertThat(refused.getOriginalBillId()).isEqualTo(created.getVendorBillId());
                        assertThat(refused.getMessage())
                                .isEqualTo("Bill " + created.getBillNumber()
                                        + " from Acme Tire dated 2026-10-01 already exists (PENDING_RECEIPT_MATCH)");
                    });
        });

        assertThat(billCount(vendor)).as("rolled back").isZero();
    }

    // ---- criteria 12 and 4 on Postgres: the writer that loses the race ---------------------------
    //
    // A mock transaction manager cannot see what happens to a transaction Postgres has aborted. These
    // cases run the production paths against a bill another writer has already committed, with a guard
    // whose first look misses it (RaceLosingGuard), so the insert is refused by the index itself.

    @Test
    @DisplayName(
            "criterion 12: a listener insert refused by the index rolls back, runs once more and records the duplicate")
    void listenerThatLosesTheRaceRunsOnceMoreAgainstTheCommittedBill() {
        UUID original = insertBill(TENANT_A, vendor, "INV-1", OCT_1_MORNING, "APPROVED");
        RaceLosingGuard racing = new RaceLosingGuard(guard, bills);
        listener = listener(racing);

        String eventId = deliver(TENANT_A, "inv-1", "2026-10-01", "100.00");

        assertThat(racing.looks())
                .as("the blind first run, then the run that sees the committed bill")
                .isEqualTo(2);
        assertThat(billCount(vendor)).isEqualTo(1);
        assertThat(status(original)).isEqualTo("APPROVED");
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM processed_events WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1);
        // One record, against the original: the first run's record rolled back with its bill.
        assertThat(owner.queryForMap(
                        "SELECT status, idempotency_outcome FROM accounting_event WHERE domain_key_id = ?",
                        original.toString()))
                .containsEntry("status", "PROCESSED")
                .containsEntry("idempotency_outcome", "DUPLICATE_IGNORED");
        // S24: ap_vendor is retired; the first run left nothing behind but the rolled-back bill.
        assertThat(owner.queryForObject("SELECT to_regclass('public.ap_vendor') IS NULL", Boolean.class))
                .as("ap_vendor no longer exists")
                .isTrue();
    }

    @ParameterizedTest(name = "inside a caller's transaction: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("criterion 4: a goods-receipt insert refused by the index is answered with the committed original")
    void goodsReceiptThatLosesTheRaceIsRefusedWithTheCommittedOriginal(boolean insideCallersTransaction) {
        // Two bills have been numbered, so the counter row exists and the next number is 3: the one
        // the competing writer's bill already holds.
        VendorBillService numbering = committingService();
        UUID earlierVendor = anotherVendor(TENANT_A);
        create(numbering, TENANT_A, earlierVendor);
        create(numbering, TENANT_A, earlierVendor);
        long next = nextBillSequence(TENANT_A);
        assertThat(next).isEqualTo(3L);
        String number = String.format(
                "BILL_%s_%s_%07d",
                vendor.toString().substring(0, 8).toUpperCase(Locale.ROOT),
                LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE),
                next);
        UUID original = insertBill(TENANT_A, vendor, number, OCT_1_EVENING, "APPROVED");
        RaceLosingGuard racing = new RaceLosingGuard(guard, bills);
        VendorBillService service = raceLosingService(racing);
        GoodsReceivedEvent event = goodsReceived(UUID.randomUUID());

        // Alone, the service's own transaction has rolled back before the original is read. Inside a
        // caller's transaction (VendorBillEventHandler.onGoodsReceived is @Transactional) it is only
        // marked rollback-only and Postgres has aborted it: the read works because it is REQUIRES_NEW.
        ThrowingCallable call = insideCallersTransaction
                ? () -> inTenant(TENANT_A, () -> service.handleGoodsReceivedEvent(event))
                : () -> asTenant(TENANT_A, () -> service.handleGoodsReceivedEvent(event));

        assertThatThrownBy(call).isInstanceOfSatisfying(VendorBillDuplicateException.class, refused -> {
            assertThat(refused.getOriginalBillId()).isEqualTo(original);
            assertThat(refused.getMessage())
                    .isEqualTo("Bill " + number + " from Acme Tire dated 2026-10-01 already exists (APPROVED)");
        });
        assertThat(racing.looks())
                .as("the blind pre-check only; the original is read after the collision")
                .isEqualTo(1);
        assertThat(billCount(vendor)).isEqualTo(1);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM vendor_bill_line WHERE vendor_bill_id IN"
                                + " (SELECT vendor_bill_id FROM vendor_bill WHERE vendor_id = ?)",
                        Long.class,
                        vendor))
                .isZero();
        // The refused attempt's increment rolled back with it: the number is not consumed, and the
        // counter row's lock is released, so the next bill (another vendor's) takes that number.
        assertThat(nextBillSequence(TENANT_A)).isEqualTo(next);
        assertThat(sequenceOf(create(numbering, TENANT_A, earlierVendor))).isEqualTo(next);
        assertThat(nextBillSequence(TENANT_A)).isEqualTo(next + 1);
    }

    @Test
    @DisplayName(
            "a match that loses the race is stopped by the index: the goods-receipt bill is unchanged, one bill holds"
                    + " the number")
    void matchThatLosesTheRaceIsStoppedByTheIndex() {
        UUID product = UUID.randomUUID();
        UUID goodsReceiptBill = inTenant(TENANT_A, () -> {
            VendorBill bill = bills.saveAndFlush(newBill("BILL_GR_1", OCT_1_MORNING));
            VendorBillLine line = new VendorBillLine();
            line.setVendorBill(bill);
            line.setLineNumber(1);
            line.setProductId(product);
            line.setDescription("Brake pads");
            line.setQuantity(new BigDecimal("10"));
            line.setUnitPrice(new BigDecimal("10.00"));
            line.setLineTotal(new BigDecimal("100.00"));
            billLines.saveAndFlush(line);
            return bill.getVendorBillId();
        });
        UUID other = insertBill(TENANT_A, vendor, "INV-77", OCT_1_EVENING, "APPROVED");
        RaceLosingGuard racing = new RaceLosingGuard(guard, bills);
        VendorBillService service = raceLosingService(racing);
        VendorInvoiceReceivedEvent invoice = VendorInvoiceReceivedEvent.builder()
                .eventId(UUID.randomUUID())
                .organizationId(UUID.randomUUID())
                .vendorId(vendor)
                .invoiceReference("inv 77")
                .invoiceDate(OCT_1_MORNING)
                .dueDate(OCT_1_MORNING.plusDays(30))
                .lineItems(List.of(VendorInvoiceReceivedEvent.InvoiceLineItem.builder()
                        .productId(product)
                        .description("Brake pads")
                        .quantity(new BigDecimal("10"))
                        .unitPrice(new BigDecimal("10.00"))
                        .build()))
                .build();

        // The enclosing transaction stands in for the service's @Transactional proxy. The rename is
        // flushed at its commit, where the index refuses it: this is the violation the platform's
        // handler answers as the generic 409 DUPLICATE_RESOURCE. The match path does not translate it.
        assertThatThrownBy(() -> inTenant(TENANT_A, () -> service.handleVendorInvoiceReceivedEvent(invoice)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .matches(VendorBillDuplicateGuard::isDuplicateRuleViolation, "names " + INDEX);

        assertThat(racing.looks()).isEqualTo(1);
        assertThat(owner.queryForMap(
                        "SELECT bill_number, bill_number_key, status, approved_by, due_date FROM vendor_bill"
                                + " WHERE vendor_bill_id = ?",
                        goodsReceiptBill))
                .containsEntry("bill_number", "BILL_GR_1")
                .containsEntry("bill_number_key", "BILLGR1")
                .containsEntry("status", "PENDING_RECEIPT_MATCH")
                .containsEntry("approved_by", null)
                .containsEntry("due_date", null);
        assertThat(owner.queryForList(
                        "SELECT vendor_bill_id FROM vendor_bill WHERE vendor_id = ? AND bill_number_key = 'INV77'",
                        UUID.class,
                        vendor))
                .containsExactly(other);
    }

    private VendorBillService raceLosingService(VendorBillDuplicateGuard racing) {
        return new VendorBillServiceImpl(
                clock,
                bills,
                billLines,
                matchCandidates,
                vendorCopies,
                racing,
                sequenceLocker,
                transactionManager,
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(java.time.Clock.systemUTC()),
                matcher,
                reader,
                auditLogs,
                locks,
                autoApproval);
    }

    /**
     * The guard of a writer that loses the race: its first look sees no original, as a pre-check does
     * when the competing writer commits a moment later. Every other look, and the read after the
     * collision, is the real Spring-proxied guard, so that read runs in its own {@code REQUIRES_NEW}
     * transaction as {@code pos_app} under row-level security, exactly as in production.
     */
    private static final class RaceLosingGuard extends VendorBillDuplicateGuard {

        private final VendorBillDuplicateGuard real;
        private final AtomicInteger looks = new AtomicInteger();

        @SuppressWarnings("unchecked")
        RaceLosingGuard(VendorBillDuplicateGuard real, VendorBillRepository bills) {
            super(bills, org.mockito.Mockito.mock(ObjectProvider.class));
            this.real = real;
        }

        int looks() {
            return looks.get();
        }

        @Override
        public Optional<VendorBill> findOriginal(
                UUID vendorId, String billNumber, LocalDateTime billDate, UUID excludeBillId) {
            if (looks.getAndIncrement() == 0) {
                return Optional.empty();
            }
            return real.findOriginal(vendorId, billNumber, billDate, excludeBillId);
        }

        @Override
        public Optional<VendorBill> findOriginalAfterCollision(
                UUID vendorId, String billNumber, LocalDateTime billDate) {
            return real.findOriginalAfterCollision(vendorId, billNumber, billDate);
        }
    }

    // ---- goods-receipt numbering: one counter per tenant (ADR-0062 section 9) --------------------
    //
    // These cases commit, because what they prove is what survives a commit. They build the service
    // with a publisher that drops the GL posting event, so the bills are the only thing they leave in
    // the database the other tests share; criterion 14 above keeps the full wiring.

    @Test
    @DisplayName("each tenant draws its own gap-free numbers: tenant B starts at 1 whatever tenant A has drawn")
    void goodsReceiptNumbersArePerTenantAndGapFree() {
        VendorBillService service = committingService();
        UUID tenantBVendor = anotherVendor(TENANT_B);

        long a1 = sequenceOf(create(service, TENANT_A, vendor));
        long a2 = sequenceOf(create(service, TENANT_A, vendor));
        long a3 = sequenceOf(create(service, TENANT_A, vendor));
        long b1 = sequenceOf(create(service, TENANT_B, tenantBVendor));
        long a4 = sequenceOf(create(service, TENANT_A, vendor));
        long b2 = sequenceOf(create(service, TENANT_B, tenantBVendor));

        assertThat(List.of(a1, a2, a3, a4)).containsExactly(1L, 2L, 3L, 4L);
        assertThat(List.of(b1, b2)).containsExactly(1L, 2L);
        assertThat(nextBillSequence(TENANT_A)).isEqualTo(5L);
        assertThat(nextBillSequence(TENANT_B)).isEqualTo(3L);
        assertThat(owner.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relkind = 'S' AND relname = 'bill_number_seq'",
                        Long.class))
                .as("no shared database sequence numbers bills")
                .isZero();
    }

    @Test
    @DisplayName("concurrent creates in one tenant never draw the same number, and leave no gap")
    void concurrentCreatesInOneTenantNeverShareANumber() throws Exception {
        VendorBillService service = committingService();
        int writers = 6;
        int billsEach = 2;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<List<String>>> drawn = new ArrayList<>();
            for (int writer = 0; writer < writers; writer++) {
                drawn.add(pool.submit(() -> {
                    start.await();
                    List<String> numbers = new ArrayList<>();
                    for (int bill = 0; bill < billsEach; bill++) {
                        numbers.add(create(service, TENANT_A, vendor));
                    }
                    return numbers;
                }));
            }
            start.countDown();
            List<String> numbers = new ArrayList<>();
            for (Future<List<String>> writer : drawn) {
                numbers.addAll(writer.get(60, TimeUnit.SECONDS));
            }

            assertThat(numbers).hasSize(writers * billsEach).doesNotHaveDuplicates();
            assertThat(numbers.stream().map(this::sequenceOf))
                    .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
            assertThat(nextBillSequence(TENANT_A)).isEqualTo(13L);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName(
            "S24: another tenant's copy of a vendor is not this tenant's: the create is refused VENDOR_NOT_FOUND and"
                    + " writes nothing")
    void vendorInAnotherTenantsCopyIsNotThisTenants() {
        VendorBillService service = committingService();

        // The vendor is in tenant A's copy only; row-level security hides it from tenant B.
        assertThatThrownBy(() -> create(service, TENANT_B, vendor))
                .isInstanceOfSatisfying(
                        VendorBillException.class,
                        refused -> assertThat(refused.getCode()).isEqualTo(VendorBillException.Code.VENDOR_NOT_FOUND));

        assertThat(owner.queryForList("SELECT tenant_id FROM vendor_bill WHERE vendor_id = ?", UUID.class, vendor))
                .isEmpty();
        assertThat(nextBillSequence(TENANT_B)).as("no number was drawn").isEqualTo(1L);
    }

    /** The production service, wired by hand with the test's zone (creation posts nothing, AW37). */
    private VendorBillService committingService() {
        return new VendorBillServiceImpl(
                clock,
                bills,
                billLines,
                matchCandidates,
                vendorCopies,
                guard,
                sequenceLocker,
                transactionManager,
                com.positivity.accounting.internal.service.TestZoneResolvers.utc(java.time.Clock.systemUTC()),
                matcher,
                reader,
                auditLogs,
                locks,
                autoApproval);
    }

    private String create(VendorBillService service, UUID tenant, UUID vendorId) {
        return asTenant(
                tenant,
                () -> service.handleGoodsReceivedEvent(goodsReceived(UUID.randomUUID(), vendorId))
                        .getBillNumber());
    }

    private long sequenceOf(String billNumber) {
        Matcher number = Pattern.compile("BILL_[0-9A-F]{8}_\\d{8}_(\\d{7})").matcher(billNumber);
        assertThat(number.matches()).as(billNumber).isTrue();
        return Long.parseLong(number.group(1));
    }

    /** The scope the service numbers this month's goods-receipt bills under. */
    private String billScope() {
        return "BILL-" + LocalDate.now(clock).format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    /** The next number the tenant's counter would hand out; 1 while the tenant has no counter row yet. */
    private long nextBillSequence(UUID tenant) {
        List<Long> next = owner.queryForList(
                "SELECT next_value FROM accounting_sequence WHERE tenant_id = ? AND scope_key = ?",
                Long.class,
                tenant,
                billScope());
        return next.isEmpty() ? 1L : next.get(0);
    }

    private UUID anotherVendor(UUID tenant) {
        UUID another = UUID.randomUUID();
        vendors.add(another);
        copyVendor(tenant, another);
        return another;
    }

    // ---- criterion 15: tenants -------------------------------------------------------------------

    @Test
    @DisplayName("criterion 15: the rule is per tenant; another tenant records the same vendor, number and date")
    void anotherTenantRecordsTheSameBill() {
        insertBill(TENANT_A, vendor, "INV-1", OCT_1_MORNING, "APPROVED");

        assertThat(inTenant(TENANT_B, () -> guard.findOriginal(vendor, "INV-1", OCT_1_MORNING, null)))
                .isEmpty();
        UUID tenantBBill = inTenant(
                TENANT_B,
                () -> bills.saveAndFlush(newBill("INV-1", OCT_1_MORNING)).getVendorBillId());

        assertThat(owner.queryForList(
                        "SELECT tenant_id FROM vendor_bill WHERE vendor_id = ? ORDER BY tenant_id", UUID.class, vendor))
                .containsExactly(TENANT_A, TENANT_B);
        assertThat(tenantBBill).isNotNull();
        // And tenant A is still refused its own second copy.
        assertThat(inTenant(TENANT_A, () -> guard.findOriginal(vendor, "INV-1", OCT_1_MORNING, null)))
                .isPresent();
    }

    // ---- criterion 13: the migration's guard -----------------------------------------------------

    @Test
    @DisplayName(
            "criterion 13: V4 stops on existing violations, names a group and changes nothing; it runs once they are"
                    + " resolved")
    void migrationStopsOnExistingViolationsAndChangesNothing() {
        DataSource isolated = AccountingPostgresContainer.ownerDataSource("vendor-bill-duplicate-guard");
        JdbcTemplate jdbc = new JdbcTemplate(isolated);
        Flyway.configure()
                .placeholders(com.positivity.accounting.AccountingMigrations.placeholders())
                .dataSource(isolated)
                .locations(com.positivity.accounting.AccountingMigrations.releasedUpTo(3))
                .target("3")
                .load()
                .migrate();
        UUID first = insertBillBeforeV4(jdbc, "INV-00123", OCT_1_MORNING, "APPROVED");
        UUID second = insertBillBeforeV4(jdbc, "inv 00123", OCT_1_EVENING, "PENDING_RECEIPT_MATCH");
        // Not violations: a voided twin, and the same number on another date.
        insertBillBeforeV4(jdbc, "INV/00123", OCT_1_EVENING, "VOIDED");
        insertBillBeforeV4(jdbc, "INV-00123", OCT_1_EVENING.plusYears(1), "PAID");
        List<Map<String, Object>> before = jdbc.queryForList("SELECT * FROM vendor_bill ORDER BY vendor_bill_id");

        Flyway latest = Flyway.configure()
                .placeholders(com.positivity.accounting.AccountingMigrations.placeholders())
                .dataSource(isolated)
                .locations("classpath:db/migration")
                .load();
        assertThatThrownBy(latest::migrate)
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("V4 vendor bill duplicate rule: 1 group(s)")
                .hasMessageContaining(
                        "tenant " + TENANT_A + ", vendor " + vendor + ", key \"INV00123\", date 2026-10-01: 2 bills")
                .hasMessageContaining("never edits a bill");

        // BR-7: no row and no column has changed, and the migration is not recorded.
        assertThat(jdbc.queryForList("SELECT * FROM vendor_bill ORDER BY vendor_bill_id"))
                .isEqualTo(before);
        assertThat(before.get(0)).doesNotContainKey("bill_number_key");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = 'public' AND indexname = ?",
                        Long.class,
                        INDEX))
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM flyway_schema_history WHERE version = '4' AND success", Long.class))
                .isZero();

        // The remedy is a person's: one of the two is voided, and the migration then goes through.
        jdbc.update("UPDATE vendor_bill SET status = 'VOIDED' WHERE vendor_bill_id = ?", second);
        latest.migrate();

        assertThat(jdbc.queryForList(
                        "SELECT bill_number_key FROM vendor_bill ORDER BY bill_date, bill_number", String.class))
                .containsOnly("INV00123");
        assertThat(jdbc.queryForObject("SELECT status FROM vendor_bill WHERE vendor_bill_id = ?", String.class, first))
                .isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM pg_class WHERE relkind = 'S' AND relname = 'bill_number_seq'",
                        Long.class))
                .as("V4 creates no shared sequence: bills are numbered per tenant (ADR-0062 section 9)")
                .isZero();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private <T> T inTenant(UUID tenant, Supplier<T> work) {
        return asTenant(tenant, () -> new TransactionTemplate(transactionManager).execute(status -> work.get()));
    }

    private void rolledBack(UUID tenant, Runnable work) {
        asTenant(
                tenant,
                () -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    status.setRollbackOnly();
                    work.run();
                }));
    }

    /** A bill written by the owner, around the service and the entity, with the key the entity would store. */
    private UUID insertBill(UUID tenant, UUID vendorId, String billNumber, LocalDateTime billDate, String status) {
        UUID id = UUID.randomUUID();
        owner.update(
                "INSERT INTO vendor_bill (tenant_id, vendor_bill_id, vendor_id, vendor_name, bill_number,"
                        + " bill_number_key, bill_date, total_amount, currency, status, created_at, modified_at,"
                        + " created_by, modified_by) VALUES (?, ?, ?, 'Acme Tire', ?, ?, ?, 100.00, 'USD', ?, TIMESTAMPTZ"
                        + " '2026-09-01 00:00:00+00', TIMESTAMPTZ '2026-09-01 00:00:00+00', 'test', 'test')",
                tenant,
                id,
                vendorId,
                billNumber,
                VendorBillNumbers.normalise(billNumber),
                billDate,
                status);
        return id;
    }

    /** The same row on a database still at V3, where there is no key column yet. */
    private UUID insertBillBeforeV4(JdbcTemplate jdbc, String billNumber, LocalDateTime billDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO vendor_bill (tenant_id, vendor_bill_id, vendor_id, vendor_name, bill_number, bill_date,"
                        + " total_amount, currency, status, created_at, modified_at, created_by, modified_by)"
                        + " VALUES (?, ?, ?, 'Acme Tire', ?, ?, 100.00, 'USD', ?, TIMESTAMPTZ '2026-09-01 00:00:00+00',"
                        + " TIMESTAMPTZ '2026-09-01 00:00:00+00', 'test', 'test')",
                TENANT_A,
                id,
                vendor,
                billNumber,
                billDate,
                status);
        return id;
    }

    private VendorBill newBill(String billNumber, LocalDateTime billDate) {
        VendorBill bill = new VendorBill();
        bill.setVendorId(vendor);
        bill.setVendorName("Acme Tire");
        bill.setBillNumber(billNumber);
        bill.setBillDate(billDate);
        bill.setTotalAmount(new BigDecimal("100.00"));
        bill.setCurrency("USD");
        bill.setStatus(VendorBillStatus.PENDING_RECEIPT_MATCH);
        bill.setCreatedBy("test");
        bill.setModifiedBy("test");
        return bill;
    }

    private GoodsReceivedEvent goodsReceived(UUID eventId) {
        return goodsReceived(eventId, vendor);
    }

    private GoodsReceivedEvent goodsReceived(UUID eventId, UUID vendorId) {
        return GoodsReceivedEvent.builder()
                .eventId(eventId)
                .organizationId(UUID.randomUUID())
                .purchaseOrderId(UUID.randomUUID())
                .vendorId(vendorId)
                .vendorName("Acme Tire")
                .receivedDate(OCT_1_MORNING)
                .lineItems(List.of(GoodsReceivedEvent.ReceivedLineItem.builder()
                        .productId(UUID.randomUUID())
                        .description("Brake pads")
                        .quantity(new BigDecimal("10"))
                        .unitPrice(new BigDecimal("24.99"))
                        .isInventoryItem(true)
                        .build()))
                .build();
    }

    /** Delivers one {@code supplier.invoice.received} fact as the listener would receive it. */
    private String deliver(UUID tenant, String invoiceNumber, String invoiceDate, String total) {
        String eventId = UUID.randomUUID().toString();
        eventIds.add(eventId);
        String message = """
            {"eventId":"%s","eventType":"supplier.invoice.received","payload":{
              "vendorProfileId":"%s","supplierRef":"acme-tire","vendorInvoiceNumber":"%s",
              "invoiceDate":"%s","type":"INVOICE","currency":"USD",
              "totalNetAmount":%s,"totalTaxAmount":0.00,"totalGrossAmount":%s,
              "vendorOrderReference":"PO-778","occurredAt":"2026-10-01T08:00:00Z","lines":[],
              "vendorId":"%s"}}
            """.formatted(eventId, UUID.randomUUID(), invoiceNumber, invoiceDate, total, total, vendor);
        asTenant(tenant, () -> listener.onSupplierEvent(message));
        return eventId;
    }

    private long billCount(UUID vendorId) {
        return Optional.ofNullable(owner.queryForObject(
                        "SELECT count(*) FROM vendor_bill WHERE vendor_id = ?", Long.class, vendorId))
                .orElse(0L);
    }

    private String status(UUID billId) {
        return owner.queryForObject("SELECT status FROM vendor_bill WHERE vendor_bill_id = ?", String.class, billId);
    }
}
