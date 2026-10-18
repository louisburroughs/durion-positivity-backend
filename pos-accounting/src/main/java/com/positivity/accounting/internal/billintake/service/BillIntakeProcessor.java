package com.positivity.accounting.internal.billintake.service;

import com.positivity.accounting.internal.billintake.config.BillIntakeProperties;
import com.positivity.accounting.internal.billintake.entity.BillIntakeItem;
import com.positivity.accounting.internal.billintake.enums.BillFieldState;
import com.positivity.accounting.internal.billintake.enums.BillExtractionOutcome;
import com.positivity.accounting.internal.billintake.enums.BillIntakeChannel;
import com.positivity.accounting.internal.billintake.enums.BillIntakeRejectionReason;
import com.positivity.accounting.internal.billintake.enums.BillIntakeStatus;
import com.positivity.accounting.internal.billintake.enums.BillVendorResolution;
import com.positivity.accounting.internal.billintake.repository.BillIntakeItemRepository;
import com.positivity.accounting.internal.service.TenantLocaleResolver;
import com.positivity.tenancy.TenantIterator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Moves uploaded files through scan, inspection and reading (CAP:550 S25, #2518; ADR-0070 Decision 3; rulings 1 and 2).
 * Per tenant ({@link TenantIterator}), one item per transaction, each claimed with {@code SELECT ... FOR UPDATE SKIP
 * LOCKED} so concurrent instances skip a row another holds.
 *
 * <ul>
 *   <li><b>QUARANTINED:</b> scan. {@code INFECTED} is {@code FILE_REJECTED / MALWARE_FOUND}, {@code ERROR} is {@code
 *       SCAN_FAILED}, both with the bytes kept 30 days; {@code UNAVAILABLE} leaves it quarantined and backs off.
 *       Clean: inspect. {@code REJECTED} is {@code FILE_REJECTED} with the worker's reason; {@code UNAVAILABLE} stays
 *       and backs off; {@code ACCEPTED} promotes the file and the item becomes {@code EXTRACTING}.
 *   <li><b>EXTRACTING:</b> read. A reading that failed or was not read leaves a blank draft, every field {@code
 *       CHECK_THIS}, {@code extractionOutcome MANUAL}, {@code lastExtractionFailed}; {@code UNAVAILABLE} is retried
 *       until the retry window since the upload, then handled the same way. Mapping a successful reading into the
 *       read-back is S26's adapter (Part D): until it lands the draft is left blank with reason {@code
 *       RESULT_MAPPING_PENDING}.
 *   <li>Every draft leaves for {@code READY_FOR_REVIEW} through {@link BillIntakeChecks}.
 * </ul>
 *
 * <p><b>An outage never rejects</b> (ruling 2): a file whose scan or inspection cannot be asked for waits, however long,
 * and the stats pass warns and sets the {@code quarantine_age_seconds} gauge once the oldest has waited past {@code
 * quarantine-alert-after}. Imports and EDI drafts are not this processor's: imports are Part D's, EDI drafts are born
 * ready. Logs name the reference, sha-256, sizes and verdicts, never a file name or bytes (ADR-0072).
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "pos.accounting.bill-intake.processor",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class BillIntakeProcessor {

    private static final String SYSTEM = "SYSTEM";
    private static final Duration BASE_BACKOFF = Duration.ofSeconds(30);

    /** The read-back fields of a blank draft; each is empty and {@code CHECK_THIS}. */
    static final List<String> HEADER_FIELDS = List.of(
            "vendor",
            "billNumber",
            "billDate",
            "dueDate",
            "terms",
            "currency",
            "netAmount",
            "taxAmount",
            "freight",
            "totalAmount",
            "purchaseOrderNumber",
            "deliveryReference");

    private final BillIntakeItemRepository items;
    private final FileStore fileStore;
    private final MalwareScanner scanner;
    private final ExtractionPort extraction;
    private final BillIntakeChecks checks;
    private final BillIntakeAuditRecorder audit;
    private final BillIntakeMetrics metrics;
    private final BillIntakeProperties properties;
    private final TenantIterator tenantIterator;
    private final TenantLocaleResolver localeResolver;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public BillIntakeProcessor(
            BillIntakeItemRepository items,
            FileStore fileStore,
            MalwareScanner scanner,
            ExtractionPort extraction,
            BillIntakeChecks checks,
            BillIntakeAuditRecorder audit,
            BillIntakeMetrics metrics,
            BillIntakeProperties properties,
            TenantIterator tenantIterator,
            TenantLocaleResolver localeResolver,
            PlatformTransactionManager transactionManager,
            Clock clock) {
        this.items = items;
        this.fileStore = fileStore;
        this.scanner = scanner;
        this.extraction = extraction;
        this.checks = checks;
        this.audit = audit;
        this.metrics = metrics;
        this.properties = properties;
        this.tenantIterator = tenantIterator;
        this.localeResolver = localeResolver;
        this.transaction = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** One pass over every active tenant. */
    @Scheduled(
            fixedDelayString = "${pos.accounting.bill-intake.processor.interval:PT30S}",
            initialDelayString = "${pos.accounting.bill-intake.processor.initial-delay:PT1M}")
    public void process() {
        tenantIterator.forEachActiveTenant(tenantId -> processTenant());
    }

    /** One pass over the bound tenant: at most {@code batch} items per step, each in a transaction of its own. */
    public void processTenant() {
        for (int i = 0; i < properties.getProcessor().getBatch(); i++) {
            if (!Boolean.TRUE.equals(transaction.execute(_ -> advanceQuarantined()))) {
                break;
            }
        }
        for (int i = 0; i < properties.getProcessor().getBatch(); i++) {
            if (!Boolean.TRUE.equals(transaction.execute(_ -> advanceExtracting()))) {
                break;
            }
        }
    }

    /** The fleet-wide gauges and the outage alert, refreshed on their own, slower schedule. */
    @Scheduled(
            fixedDelayString = "${pos.accounting.bill-intake.processor.stats-interval:PT5M}",
            initialDelayString = "${pos.accounting.bill-intake.processor.initial-delay:PT1M}")
    public void refreshStats() {
        Map<String, Long> totals = new HashMap<>();
        AtomicLong oldest = new AtomicLong();
        tenantIterator.forEachActiveTenant(tenantId -> transaction.executeWithoutResult(_ -> {
            for (BillIntakeStatus status : BillIntakeStatus.values()) {
                for (BillIntakeChannel channel : BillIntakeChannel.values()) {
                    totals.merge(
                            BillIntakeMetrics.key(status, channel),
                            items.countByStatusAndChannel(status, channel),
                            Long::sum);
                }
            }
            items.findFirstByStatusOrderByUploadedAtAscIntakeIdAsc(BillIntakeStatus.QUARANTINED)
                    .ifPresent(item -> {
                        long age = Duration.between(item.getUploadedAt(), Instant.now(clock))
                                .toSeconds();
                        oldest.accumulateAndGet(age, Math::max);
                        if (age >= properties.getQuarantineAlertAfter().toSeconds()) {
                            log.warn(
                                    "Bill file quarantined past the alert threshold | tenant={} | oldestAgeSeconds={}"
                                            + " | thresholdSeconds={} (scanner or extraction worker unreachable?)",
                                    tenantId,
                                    age,
                                    properties.getQuarantineAlertAfter().toSeconds());
                        }
                    });
        }));
        metrics.publish(totals, oldest.get());
    }

    // ---- QUARANTINED: scan, inspect, promote -----------------------------------------------------------------

    /** Handles the oldest due quarantined upload of the bound tenant; false when there is none. */
    boolean advanceQuarantined() {
        Instant now = Instant.now(clock);
        List<BillIntakeItem> claimed = items.claimDue(
                BillIntakeStatus.QUARANTINED, BillIntakeChannel.UPLOAD, now, PageRequest.of(0, 1));
        if (claimed.isEmpty()) {
            return false;
        }
        BillIntakeItem item = claimed.get(0);
        Optional<FileStore.StoredContent> content =
                item.getSourceFileId() == null ? Optional.empty() : fileStore.open(item.getSourceFileId());
        if (content.isEmpty()) {
            // The bytes are gone: nothing to scan, and nothing can ever be read from this item.
            reject(item, BillIntakeRejectionReason.UNREADABLE, now);
            return true;
        }
        byte[] bytes = content.get().bytes();
        MalwareScanner.ScanVerdict verdict = scanner.scan(bytes);
        metrics.scan(verdict);
        audit.record(
                item.getIntakeId(),
                BillIntakeAuditRecorder.SCAN,
                SYSTEM,
                null,
                null,
                "{\"sha256\":\"" + content.get().sha256() + "\",\"verdict\":\"" + verdict + "\"}");
        log.info(
                "Bill file scanned | intakeReference={} | sha256={} | sizeBytes={} | verdict={}",
                item.getIntakeReference(),
                content.get().sha256(),
                content.get().sizeBytes(),
                verdict);
        switch (verdict) {
            case INFECTED -> reject(item, BillIntakeRejectionReason.MALWARE_FOUND, now);
            case ERROR -> reject(item, BillIntakeRejectionReason.SCAN_FAILED, now);
            case UNAVAILABLE -> backOff(item, now);
            case CLEAN -> inspect(item, bytes, now);
        }
        return true;
    }

    private void inspect(BillIntakeItem item, byte[] bytes, Instant now) {
        Optional<BillFileTypes.Type> type = BillFileTypes.detect(bytes);
        if (type.isEmpty()) {
            reject(item, BillIntakeRejectionReason.TYPE_MISMATCH, now);
            return;
        }
        ExtractionPort.InspectionResult result = extraction.inspect(bytes, type.get().declaredType());
        switch (result.verdict()) {
            case REJECTED -> reject(item, BillIntakeRejectionReason.ofWorkerCode(result.reasonCode()), now);
            case UNAVAILABLE -> backOff(item, now);
            case ACCEPTED -> {
                fileStore.promote(item.getSourceFileId(), result.pageCount() > 0 ? result.pageCount() : null);
                item.setStatus(BillIntakeStatus.EXTRACTING);
                item.setAttempts(0);
                item.setNextAttemptAt(null);
                items.save(item);
            }
        }
    }

    private void reject(BillIntakeItem item, BillIntakeRejectionReason reason, Instant now) {
        if (item.getSourceFileId() != null) {
            fileStore.reject(item.getSourceFileId(), now.plus(properties.getRejectedRetention()));
        }
        item.setStatus(BillIntakeStatus.FILE_REJECTED);
        item.setRejectionReason(reason.name());
        item.setNextAttemptAt(null);
        items.save(item);
        log.info(
                "Bill file rejected | intakeReference={} | reason={}", item.getIntakeReference(), reason);
    }

    private void backOff(BillIntakeItem item, Instant now) {
        int attempts = item.getAttempts() + 1;
        item.setAttempts(attempts);
        Duration delay = BASE_BACKOFF.multipliedBy(1L << Math.min(attempts - 1, 16));
        Duration max = properties.getProcessor().getMaxBackoff();
        item.setNextAttemptAt(now.plus(delay.compareTo(max) > 0 ? max : delay));
        items.save(item);
    }

    // ---- EXTRACTING: read, check, hand to a person -----------------------------------------------------------

    /** Handles the oldest due extracting upload of the bound tenant; false when there is none. */
    boolean advanceExtracting() {
        Instant now = Instant.now(clock);
        List<BillIntakeItem> claimed = items.claimDue(
                BillIntakeStatus.EXTRACTING, BillIntakeChannel.UPLOAD, now, PageRequest.of(0, 1));
        if (claimed.isEmpty()) {
            return false;
        }
        BillIntakeItem item = claimed.get(0);
        Optional<FileStore.StoredContent> content =
                item.getSourceFileId() == null ? Optional.empty() : fileStore.open(item.getSourceFileId());
        Optional<BillFileTypes.Type> type = content.flatMap(c -> BillFileTypes.detect(c.bytes()));
        if (type.isEmpty()) {
            leaveBlank(item, true, "SOURCE_UNREADABLE", null);
            return true;
        }
        ExtractionPort.ExtractionResult result = extraction.extract(
                content.get().bytes(),
                type.get().declaredType(),
                ExtractionPort.DocumentKind.BILL,
                localeResolver.localeOfCurrentTenant());
        switch (result.outcome()) {
            case EXTRACTED -> leaveBlank(item, false, "RESULT_MAPPING_PENDING", result.provider());
            case FAILED -> leaveBlank(item, true, result.reasonCode(), result.provider());
            case UNAVAILABLE -> {
                Instant giveUpAt = item.getUploadedAt().plus(properties.getExtractionRetryWindow());
                if (now.isBefore(giveUpAt)) {
                    backOff(item, now);
                } else {
                    leaveBlank(item, true, result.reasonCode(), result.provider());
                }
            }
        }
        return true;
    }

    /** A draft nothing was read into: every field empty and CHECK_THIS, for a person to key. */
    private void leaveBlank(BillIntakeItem item, boolean lastExtractionFailed, String reason, String provider) {
        item.setReadBack(blankReadBack());
        item.setExtractionOutcome(BillExtractionOutcome.MANUAL);
        item.setLastExtractionFailed(lastExtractionFailed);
        item.setExtractionReason(reason);
        item.setExtractionProvider(provider);
        item.setVendorResolution(BillVendorResolution.NOT_SET_UP);
        checks.apply(item);
        item.setStatus(BillIntakeStatus.READY_FOR_REVIEW);
        item.setAttempts(0);
        item.setNextAttemptAt(null);
        items.save(item);
        log.info(
                "Bill draft ready for review | intakeReference={} | outcome=MANUAL | reason={}",
                item.getIntakeReference(),
                reason);
    }

    static Map<String, Object> blankReadBack() {
        Map<String, Object> readBack = new LinkedHashMap<>();
        for (String name : HEADER_FIELDS) {
            Map<String, Object> field = new LinkedHashMap<>();
            field.put("value", null);
            field.put("state", BillFieldState.CHECK_THIS.name());
            readBack.put(name, field);
        }
        readBack.put("lines", new ArrayList<>());
        return readBack;
    }
}
