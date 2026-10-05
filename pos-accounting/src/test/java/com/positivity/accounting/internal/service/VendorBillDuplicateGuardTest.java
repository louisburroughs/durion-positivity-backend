package com.positivity.accounting.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.positivity.accounting.internal.entity.VendorBill;
import com.positivity.accounting.internal.enums.VendorBillStatus;
import com.positivity.accounting.internal.exception.VendorBillDuplicateException;
import com.positivity.accounting.internal.repository.VendorBillRepository;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard.Channel;
import com.positivity.accounting.internal.service.VendorBillDuplicateGuard.Outcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * What {@link VendorBillDuplicateGuard} tells an operator (#2501, "Audit and observability"): one
 * WARN per refusal or flag and none for the outcomes nobody has to act on, one counter increment per
 * outcome, and no vendor name or amount in either.
 */
@DisplayName("VendorBillDuplicateGuard — log levels and counters (#2501)")
class VendorBillDuplicateGuardTest {

    private static final UUID VENDOR = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7a01");
    private static final UUID ORIGINAL = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7c01");
    private static final LocalDateTime BILL_DATE = LocalDateTime.of(2026, 10, 1, 9, 30);

    private final VendorBillRepository repository = mock(VendorBillRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final Logger logger = (Logger) LoggerFactory.getLogger(VendorBillDuplicateGuard.class);
    private final ListAppender<ILoggingEvent> logged = new ListAppender<>();

    private Level levelBefore;
    private VendorBillDuplicateGuard guard;

    @BeforeEach
    void setUp() {
        ObjectProvider<MeterRegistry> provider = mock();
        when(provider.getIfAvailable()).thenReturn(meters);
        guard = new VendorBillDuplicateGuard(repository, provider);
        levelBefore = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logged.start();
        logger.addAppender(logged);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(logged);
        logger.setLevel(levelBefore);
    }

    @ParameterizedTest(name = "{0} logs at {1}")
    @CsvSource({"REFUSED, WARN", "FLAGGED, WARN", "IGNORED, DEBUG", "RETRIED, INFO"})
    @DisplayName("a refusal or a flag is one WARN; an ignored duplicate and a retry are not warnings")
    void eachOutcomeLogsOnceAtItsLevel(Outcome outcome, String level) {
        guard.record(Channel.EDI, outcome, VENDOR, "inv-00123", BILL_DATE, ORIGINAL);

        assertThat(logged.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.toLevel(level));
            assertThat(line.getFormattedMessage())
                    .contains("channel=edi")
                    .contains("outcome=" + outcome.name().toLowerCase(java.util.Locale.ROOT))
                    .contains("vendorId=" + VENDOR)
                    .contains("key=INV00123")
                    .contains("billDate=2026-10-01")
                    .contains("originalBillId=" + ORIGINAL);
        });
    }

    @Test
    @DisplayName("each outcome is one increment of accounting.vendor_bill.duplicate under its channel and outcome")
    void countsByChannelAndOutcome() {
        guard.record(Channel.GOODS_RECEIPT, Outcome.REFUSED, VENDOR, "A-1", BILL_DATE, ORIGINAL);
        guard.record(Channel.EDI, Outcome.IGNORED, VENDOR, "A-1", BILL_DATE, ORIGINAL);
        guard.record(Channel.EDI, Outcome.IGNORED, VENDOR, "A-1", BILL_DATE, ORIGINAL);
        guard.record(Channel.EDI, Outcome.RETRIED, VENDOR, "A-1", BILL_DATE, null);

        assertThat(count("goods_receipt", "refused")).isEqualTo(1.0);
        assertThat(count("edi", "ignored")).isEqualTo(2.0);
        assertThat(count("edi", "retried")).isEqualTo(1.0);
        assertThat(meters.find(VendorBillDuplicateGuard.COUNTER_NAME).counters())
                .hasSize(3);
    }

    @Test
    @DisplayName("a refusal carries the original, warns once and names no vendor in the log")
    void refusalWarnsOnceAndCarriesTheOriginal() {
        VendorBill original = new VendorBill(ORIGINAL);
        original.setVendorId(VENDOR);
        original.setVendorName("Acme Tire");
        original.setBillNumber("INV-00123");
        original.setBillDate(BILL_DATE);
        original.setStatus(VendorBillStatus.APPROVED);
        when(repository.findLiveDuplicate(
                        VENDOR,
                        "INV00123",
                        LocalDateTime.of(2026, 10, 1, 0, 0),
                        LocalDateTime.of(2026, 10, 2, 0, 0),
                        null))
                .thenReturn(Optional.of(original));

        assertThatThrownBy(
                        () -> guard.refuseIfDuplicate(Channel.MATCH, VENDOR, "inv 00123", BILL_DATE.withHour(17), null))
                .isInstanceOfSatisfying(
                        VendorBillDuplicateException.class,
                        refused -> assertThat(refused.getOriginalBillId()).isEqualTo(ORIGINAL));

        assertThat(logged.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("channel=match").doesNotContain("Acme Tire");
        });
        assertThat(count("match", "refused")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no duplicate: nothing is thrown, logged or counted")
    void noDuplicateIsSilent() {
        when(repository.findLiveDuplicate(any(), any(), any(), any(), any())).thenReturn(Optional.empty());

        guard.refuseIfDuplicate(Channel.GOODS_RECEIPT, VENDOR, "INV-1", BILL_DATE, null);

        assertThat(logged.list).isEmpty();
        assertThat(meters.find(VendorBillDuplicateGuard.COUNTER_NAME).counters())
                .isEmpty();
    }

    @Test
    @DisplayName("only a violation naming uq_vendor_bill_duplicate_rule, anywhere in the cause chain, is the rule's")
    void recognisesOnlyItsOwnIndex() {
        RuntimeException driver = new RuntimeException(
                "ERROR: duplicate key value violates unique constraint \"uq_vendor_bill_duplicate_rule\"");

        assertThat(VendorBillDuplicateGuard.isDuplicateRuleViolation(
                        new DataIntegrityViolationException("could not execute statement", driver)))
                .isTrue();
        assertThat(VendorBillDuplicateGuard.isDuplicateRuleViolation(new DataIntegrityViolationException(
                        "duplicate key value violates unique constraint \"vendor_bill_pkey\"")))
                .isFalse();
        assertThat(VendorBillDuplicateGuard.isDuplicateRuleViolation(null)).isFalse();
    }

    private double count(String channel, String outcome) {
        return meters.get(VendorBillDuplicateGuard.COUNTER_NAME)
                .tag("channel", channel)
                .tag("outcome", outcome)
                .counter()
                .count();
    }
}
